package com.stacksync.syncservice.handler;

import com.ast.cloudABE.kpabe.AttributeUpdate;
import com.ast.cloudABE.kpabe.AttributeUpdateForUser;
import com.ast.cloudABE.kpabe.KPABE;
import com.ast.cloudABE.kpabe.KPABESecretKey;
import com.ast.cloudABE.kpabe.RevokeMessage;
import com.ast.cloudABE.kpabe.RevokeMessage.RevokeComponent;
import com.ast.cloudABE.kpabe.SystemKey;
import com.google.gson.Gson;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.apache.log4j.Logger;

import com.stacksync.commons.exceptions.ShareProposalNotCreatedException;
import com.stacksync.commons.exceptions.RevokeProposalNotCreatedException;
import com.stacksync.commons.exceptions.UserNotFoundException;
import com.stacksync.commons.models.ABEWorkspace;
import com.stacksync.commons.models.abe.ABEItem;
import com.stacksync.commons.models.abe.ABEItemMetadata;
import com.stacksync.commons.models.Chunk;
import com.stacksync.commons.models.CommitInfo;
import com.stacksync.commons.models.Device;
import com.stacksync.commons.models.Item;
import com.stacksync.commons.models.ItemMetadata;
import com.stacksync.commons.models.ItemVersion;
import com.stacksync.commons.models.SyncMetadata;
import com.stacksync.commons.models.User;
import com.stacksync.commons.models.UserWorkspace;
import com.stacksync.commons.models.Workspace;
import com.stacksync.commons.notifications.RevokeNotification;
import com.stacksync.syncservice.db.ABEItemDAO;
import com.stacksync.syncservice.db.ConnectionPool;
import com.stacksync.syncservice.db.DAOFactory;
import com.stacksync.syncservice.db.DeviceDAO;
import com.stacksync.syncservice.db.ItemDAO;
import com.stacksync.syncservice.db.ItemVersionDAO;
import com.stacksync.syncservice.db.UserDAO;
import com.stacksync.syncservice.db.WorkspaceDAO;
import com.stacksync.syncservice.exceptions.CommitExistantVersion;
import com.stacksync.syncservice.exceptions.CommitWrongVersion;
import com.stacksync.syncservice.exceptions.CommitWrongVersionNoParent;
import com.stacksync.syncservice.exceptions.InternalServerError;
import com.stacksync.syncservice.exceptions.dao.DAOException;
import com.stacksync.syncservice.exceptions.dao.NoResultReturnedDAOException;
import com.stacksync.syncservice.exceptions.dao.NoRowsAffectedDAOException;
import com.stacksync.syncservice.exceptions.storage.NoStorageManagerAvailable;
import com.stacksync.syncservice.exceptions.storage.ObjectNotFoundException;
import com.stacksync.syncservice.storage.StorageFactory;
import com.stacksync.syncservice.storage.StorageManager;
import com.stacksync.syncservice.storage.StorageManager.StorageType;
import com.stacksync.syncservice.util.Config;
import java.util.LinkedList;
import java.util.Map;

public class Handler {

    private static final Logger logger = Logger.getLogger(Handler.class.getName());

    protected Connection connection;
    protected WorkspaceDAO workspaceDAO;
    protected UserDAO userDao;
    protected DeviceDAO deviceDao;
    protected ItemDAO itemDao;
    protected ABEItemDAO abeItemDao;
    protected ItemVersionDAO itemVersionDao;

    protected StorageManager storageManager;

    public enum Status {

        NEW, DELETED, CHANGED, RENAMED, MOVED
    };

    public Handler(ConnectionPool pool) throws SQLException, NoStorageManagerAvailable {
        connection = pool.getConnection();

        String dataSource = Config.getDatasource();

        DAOFactory factory = new DAOFactory(dataSource);

        workspaceDAO = factory.getWorkspaceDao(connection);
        deviceDao = factory.getDeviceDAO(connection);
        userDao = factory.getUserDao(connection);
        itemDao = factory.getItemDAO(connection);
        abeItemDao = factory.getAbeItemDAO(connection);
        itemVersionDao = factory.getItemVersionDAO(connection);
        storageManager = StorageFactory.getStorageManager(StorageType.SWIFT);
    }

    public List<CommitInfo> doCommit(User user, Workspace workspace, Device device, List<SyncMetadata> items)
            throws DAOException {

        HashMap<Long, Long> tempIds = new HashMap<Long, Long>();

        workspace = workspaceDAO.getById(workspace.getId());
		// TODO: check if the workspace belongs to the user or its been given
        // access

        device = deviceDao.get(device.getId());
        // TODO: check if the device belongs to the user

        List<CommitInfo> responseObjects = new ArrayList<CommitInfo>();

        for (SyncMetadata item : items) {

            ItemMetadata objectResponse = null;
            boolean committed;

            try {

                if (item.getParentId() != null) {
                    Long parentId = tempIds.get(item.getParentId());
                    if (parentId != null) {
                        item.setParentId(parentId);
                    }
                }

				// if the item does not have ID but has a TempID, maybe it was
                // set
                if (item.getId() == null && item.getTempId() != null) {
                    Long newId = tempIds.get(item.getTempId());
                    if (newId != null) {
                        item.setId(newId);
                    }
                }

                this.commitObject(item, workspace, device);

                if (item.getTempId() != null) {
                    tempIds.put(item.getTempId(), item.getId());
                }

                objectResponse = (ItemMetadata) item;
                committed = true;
            } catch (CommitWrongVersion e) {
                Item serverObject = e.getItem();
                objectResponse = this.getCurrentServerVersion(serverObject);
                committed = false;
            } catch (CommitWrongVersionNoParent e) {
                committed = false;
            } catch (CommitExistantVersion e) {
                Item serverObject = e.getItem();
                objectResponse = this.getCurrentServerVersion(serverObject);
                committed = true;
            }

            responseObjects.add(new CommitInfo(item.getVersion(), committed, objectResponse));
        }

        return responseObjects;
    }

    public Workspace doShareFolder(User user, List<String> emails, Item item, boolean isEncrypted, boolean abeEncrypted)
            throws ShareProposalNotCreatedException, UserNotFoundException {
        return doShareFolder(user, null, null, emails, item, isEncrypted, abeEncrypted, null);
    }

    public Workspace doShareFolder(User user, byte[] publicKey, HashMap<String, HashMap<String, byte[]>> emailsKeys, List<String> emails, Item item, boolean isEncrypted, boolean abeEncrypted, Map<Integer, String> attributeUniverse)
            throws ShareProposalNotCreatedException, UserNotFoundException {

        // Check the owner
        try {
            user = userDao.findById(user.getId());
        } catch (NoResultReturnedDAOException e) {
            logger.warn(e);
            throw new UserNotFoundException(e);
        } catch (DAOException e) {
            logger.error(e);
            throw new ShareProposalNotCreatedException(e);
        }

        // Get folder metadata
        try {
            item = itemDao.findById(item.getId());
        } catch (DAOException e) {
            logger.error(e);
            throw new ShareProposalNotCreatedException(e);
        }

        if (item == null || !item.isFolder()) {
            throw new ShareProposalNotCreatedException("No folder found with the given ID.");
        }

        // Get the source workspace
        Workspace sourceWorkspace;
        try {
            sourceWorkspace = workspaceDAO.getById(item.getWorkspace().getId());
        } catch (DAOException e) {
            logger.error(e);
            throw new ShareProposalNotCreatedException(e);
        }
        if (sourceWorkspace == null) {
            throw new ShareProposalNotCreatedException("Workspace not found.");
        }

        // Check the addressees
        List<User> addressees = new ArrayList<User>();
        for (String email : emails) {
            User addressee;
            try {
                addressee = userDao.getByEmail(email);
                if (!addressee.getId().equals(user.getId())) {
                    addressees.add(addressee);
                }

            } catch (IllegalArgumentException e) {
                logger.error(e);
                throw new ShareProposalNotCreatedException(e);
            } catch (DAOException e) {
                logger.warn(String.format("Email '%s' does not correspond with any user. ", email), e);
            }
        }

        if (addressees.isEmpty()) {
            throw new ShareProposalNotCreatedException("No addressees found");
        }

        Workspace workspace;

        if (sourceWorkspace.isShared()) {
            workspace = sourceWorkspace;
            workspace.setUsers(addressees);

        } else {
            // Create the new workspace
            String container = UUID.randomUUID().toString();

            workspace = new Workspace();
            workspace.setShared(true);
            workspace.setEncrypted(isEncrypted);
            workspace.setAbeEncrypted(abeEncrypted);
            workspace.setName(item.getFilename());
            workspace.setOwner(user);
            workspace.setUsers(addressees);
            workspace.setSwiftContainer(container);
            workspace.setSwiftUrl(Config.getSwiftUrl() + "/" + user.getSwiftAccount());

            if (workspace.isAbeEncrypted()) {
                workspace = new ABEWorkspace(workspace);
                ((ABEWorkspace) workspace).setPublicKey(publicKey);
            }
            // Create container in Swift
            try {
                storageManager.createNewWorkspace(workspace);
            } catch (Exception e) {
                logger.error(e);
                throw new ShareProposalNotCreatedException(e);
            }

            // Save the workspace to the DB
            try {
                if (workspace.isAbeEncrypted()) {

                    workspaceDAO.add((ABEWorkspace) workspace);
                    workspaceDAO.addAttributeUniverse(workspace.getId(), attributeUniverse);

                    /* Not necessary
                    
                    ArrayList<AttributeUpdate> attributesVersions = new ArrayList<AttributeUpdate>();

                    for (Integer attributeId : attributeUniverse.keySet()) {
                        attributesVersions.add(new AttributeUpdate(attributeUniverse.get(attributeId), 1, null));
                    }
                    
                    workspaceDAO.addAttributeVersions(workspace.getId(), attributesVersions);
                    */

                } else {
                    workspaceDAO.add(workspace);
                }

                // add the owner to the workspace
                workspaceDAO.addUser(user, workspace);

            } catch (DAOException e) {
                logger.error(e);
                throw new ShareProposalNotCreatedException(e);
            }

            // Grant user to container in Swift
            try {
                storageManager.grantUserToWorkspace(user, user, workspace);
            } catch (Exception e) {
                logger.error(e);
                throw new ShareProposalNotCreatedException(e);
            }

            // Migrate files to new workspace
            List<String> chunks;
            try {
                chunks = itemDao.migrateItem(item.getId(), workspace.getId());
            } catch (Exception e) {
                logger.error(e);
                throw new ShareProposalNotCreatedException(e);
            }

            // Move chunks to new container
            for (String chunkName : chunks) {
                try {
                    storageManager.copyChunk(sourceWorkspace, workspace, chunkName);
                } catch (ObjectNotFoundException e) {
                    logger.error(String.format(
                            "Chunk %s not found in container %s. Could not migrate to container %s.", chunkName,
                            sourceWorkspace.getSwiftContainer(), workspace.getSwiftContainer()), e);
                } catch (Exception e) {
                    logger.error(e);
                    throw new ShareProposalNotCreatedException(e);
                }
            }
        }

        Gson gson = new Gson();
        // Add the addressees to the workspace
        for (User addressee : addressees) {
            try {
                if (workspace.isAbeEncrypted()) {
                    ((ABEWorkspace) workspace).setAccess_struct(emailsKeys.get(addressee.getEmail()).get("access_struct"));
                    ((ABEWorkspace) workspace).setSecretKey(emailsKeys.get(addressee.getEmail()).get("secret_key"));
                    workspaceDAO.addUser(addressee, (ABEWorkspace) workspace);

                    // First we create the secretKey from the workspace instance, but it lacks the access tree.
                    KPABESecretKey secretKey = gson.fromJson(new String(emailsKeys.get(addressee.getEmail()).get("secret_key")), KPABESecretKey.class);

                    ArrayList<AttributeUpdateForUser> attributeVersions = new ArrayList<AttributeUpdateForUser>();

                    for (Integer leaf : secretKey.getLeaf_keys().keySet()) {
                        attributeVersions.add(new AttributeUpdateForUser(attributeUniverse.get(leaf), 1, secretKey.getLeaf_keys().get(leaf)));
                    }

                    workspaceDAO.updateUserAttributes(workspace.getId(), addressee.getId(), attributeVersions);

                } else {
                    workspaceDAO.addUser(addressee, workspace);
                }

            } catch (DAOException e) {
                workspace.getUsers().remove(addressee);
                logger.error(String.format("An error ocurred when adding the user '%s' to workspace '%s'",
                        addressee.getId(), workspace.getId()), e);
            }

            // Grant the user to container in Swift
            try {
                storageManager.grantUserToWorkspace(user, addressee, workspace);
            } catch (Exception e) {
                logger.error(e);
                throw new ShareProposalNotCreatedException(e);
            }
        }

        return workspace;
    }

    public Map<UUID,RevokeNotification> doRevokeFolder(User user, UUID workspaceId, List<RevokeMessage> revokeMessages)
            throws UserNotFoundException, RevokeProposalNotCreatedException, DAOException {
        
        // Check the owner
        try {
            user = userDao.findById(user.getId());
        } catch (NoResultReturnedDAOException e) {
            logger.warn(e);
            throw new UserNotFoundException(e);
        } catch (DAOException e) {
            logger.error(e);
            throw new RevokeProposalNotCreatedException(e);
        }
        
         // Get the source workspace
        Workspace sourceWorkspace;
        try {
            sourceWorkspace = workspaceDAO.getById(workspaceId);
        } catch (DAOException e) {
            logger.error(e);
            throw new RevokeProposalNotCreatedException(e);
        }
        if (sourceWorkspace == null) {
            throw new RevokeProposalNotCreatedException("Workspace not found.");
        }
        if (!sourceWorkspace.isShared()) {
            throw new RevokeProposalNotCreatedException("This workspace is not shared.");
        }
        
        
        // Check the addressees
        List<User> addressees = new ArrayList<User>();
        for (RevokeMessage revokeMessage : revokeMessages) {
            User addressee;
            try {
                addressee = userDao.getByEmail(revokeMessage.getUser_id());
                revokeMessage.setUser_id(addressee.getId().toString());
                if (!addressee.getId().equals(user.getId())) {
                    addressees.add(addressee);
                }

            } catch (IllegalArgumentException e) {
                logger.error(e);
                throw new RevokeProposalNotCreatedException(e);
            } catch (DAOException e) {
                logger.warn(String.format("Email '%s' does not correspond with any user. ", revokeMessage.getUser_id()), e);
            }
        }

        if (addressees.isEmpty()) {
            throw new RevokeProposalNotCreatedException("No addressees found");
        }
               
        Gson gson = new Gson();
        SystemKey publicKey = gson.fromJson(new String(((ABEWorkspace) sourceWorkspace).getPublicKey()), SystemKey.class);
        ArrayList<AttributeUpdate> attributeVersions = new ArrayList<AttributeUpdate>();
        //Deleting user attributes for each revocation and updating workspace information
        
        for (RevokeMessage revokeMessage:revokeMessages){
            try{
                workspaceDAO.deleteUserAttributes(sourceWorkspace.getId(), UUID.fromString(revokeMessage.getUser_id()), revokeMessage.getMinimal_set());
                   
            } catch (NoRowsAffectedDAOException e){
                
            }
 
            for(RevokeComponent component:revokeMessage.getPkComponents().values()){
                publicKey.getAttribute_map().put(component.getName(), component.getPk_ti());
                //component.getVersion()-1 as the reencryption key corresponds to the previous version in order to get the new one
                attributeVersions.add(new AttributeUpdate(component.getName(), component.getVersion()-1, component.getRe_key()));
            }

        }
                
        ((ABEWorkspace) sourceWorkspace).setPublicKey(gson.toJson(publicKey).getBytes());
        workspaceDAO.addAttributeVersions(workspaceId, attributeVersions);
        workspaceDAO.updateWorkspacePublicKey(workspaceId, ((ABEWorkspace) sourceWorkspace).getPublicKey());
        
        // get workspace members
        List<UserWorkspace> workspaceMembers;
        try {
            workspaceMembers = doGetWorkspaceMembers(user, sourceWorkspace);
        } catch (InternalServerError e1) {
            throw new RevokeProposalNotCreatedException(e1.toString());
        }
 
        HashMap<String,LinkedList<AttributeUpdate>> getAttributeVersions = workspaceDAO.getAttributeVersions(workspaceId);
        Map<String,Integer> attributeUniverse = workspaceDAO.getAttributeUniverse(workspaceId);
        Map<UUID,RevokeNotification> usersNotifications = new HashMap<UUID,RevokeNotification>();
        
        KPABE kpabe = new KPABE (Config.getCurvePath());
        
        for(UserWorkspace workspaceMember:workspaceMembers){
                if(!workspaceMember.getUser().getId().equals(user.getId())){
                    HashMap<String,AttributeUpdateForUser> userAttributes = workspaceDAO.getUserAttributes(workspaceId, workspaceMember.getUser().getId());

                for(RevokeMessage revokeMessage:revokeMessages){
                    for(RevokeComponent component:revokeMessage.getPkComponents().values()){
                        if(userAttributes.get(component.getName())!=null){
                            byte[] updatedSk = kpabe.updateSK(userAttributes.get(component.getName()).getVersion()-1, userAttributes.get(component.getName()).getSk_ti(), getAttributeVersions.get(component.getName()));

                            //component.getName()).size()+1 as there isn't a reencryption key for the newest version.
                            userAttributes.put(component.getName(), new AttributeUpdateForUser(component.getName(),getAttributeVersions.get(component.getName()).size()+1,updatedSk));

                        }
                     }
                }

                workspaceDAO.updateUserAttributes(workspaceId, workspaceMember.getUser().getId(), userAttributes.values());

                byte[] secretKeyBytes = workspaceDAO.getWorkspaceUserSecretKey(workspaceId, workspaceMember.getUser().getId());

                KPABESecretKey secretKey = gson.fromJson(new String(secretKeyBytes), KPABESecretKey.class);

                for(AttributeUpdateForUser updateForUser:userAttributes.values()){
                    secretKey.getLeaf_keys().put(attributeUniverse.get(updateForUser.getAttribute()), updateForUser.getSk_ti());
                }

                secretKeyBytes = gson.toJson(secretKey).getBytes();

                usersNotifications.put(workspaceMember.getUser().getId(), new RevokeNotification(workspaceId, ((ABEWorkspace)sourceWorkspace).getPublicKey(), secretKeyBytes));

                workspaceDAO.updateWorkspaceUserSecretKey(workspaceId, workspaceMember.getUser().getId(), secretKeyBytes);
            }            
        }
        
        return usersNotifications;
        
    }

    public void doUnshareFolder(User user, List<String> emails, Item item, boolean isEncrypted)
            throws ShareProposalNotCreatedException, UserNotFoundException {

        // Check the owner
        try {
            user = userDao.findById(user.getId());
        } catch (NoResultReturnedDAOException e) {
            logger.warn(e);
            throw new UserNotFoundException(e);
        } catch (DAOException e) {
            logger.error(e);
            throw new ShareProposalNotCreatedException(e);
        }

        // Get folder metadata
        try {
            item = itemDao.findById(item.getId());
        } catch (DAOException e) {
            logger.error(e);
            throw new ShareProposalNotCreatedException(e);
        }

        if (item == null || !item.isFolder()) {
            throw new ShareProposalNotCreatedException("No folder found with the given ID.");
        }

        // Get the workspace
        Workspace sourceWorkspace;
        try {
            sourceWorkspace = workspaceDAO.getById(item.getWorkspace().getId());
        } catch (DAOException e) {
            logger.error(e);
            throw new ShareProposalNotCreatedException(e);
        }
        if (sourceWorkspace == null) {
            throw new ShareProposalNotCreatedException("Workspace not found.");
        }
        if (!sourceWorkspace.isShared()) {
            throw new ShareProposalNotCreatedException("This workspace is not shared.");
        }

        // Check the addressees
        List<User> addressees = new ArrayList<User>();
        for (String email : emails) {
            User addressee;
            try {
                addressee = userDao.getByEmail(email);
                if (!addressee.getId().equals(user.getId())) {
                    addressees.add(addressee);
                }

            } catch (IllegalArgumentException e) {
                logger.error(e);
                throw new ShareProposalNotCreatedException(e);
            } catch (DAOException e) {
                logger.warn(String.format("Email '%s' does not correspond with any user. ", email), e);
            }
        }

        if (addressees.isEmpty()) {
            throw new ShareProposalNotCreatedException("No addressees found");
        }

        // get workspace members
        List<UserWorkspace> workspaceMembers;
        try {
            workspaceMembers = doGetWorkspaceMembers(user, sourceWorkspace);
        } catch (InternalServerError e1) {
            throw new ShareProposalNotCreatedException(e1.toString());
        }

        // remove users from workspace
        List<User> usersToRemove = new ArrayList<User>();

        for (User userToRemove : addressees) {
            for (UserWorkspace member : workspaceMembers) {
                if (member.getUser().getEmail().equals(userToRemove.getEmail())) {
                    workspaceMembers.remove(member);
                    usersToRemove.add(userToRemove);
                    break;
                }
            }
        }

        if (workspaceMembers.size() <= 1) {
            // All members have been removed from the workspace

            Workspace defaultWorkspace;
            try {
                defaultWorkspace = workspaceDAO.getDefaultWorkspaceByUserId(user.getId());
            } catch (DAOException e) {
                logger.error(e);
                throw new ShareProposalNotCreatedException("Could not get default workspace");
            }

            // Migrate files to new workspace
            List<String> chunks;
            try {
                chunks = itemDao.migrateItem(item.getId(), defaultWorkspace.getId());
            } catch (Exception e) {
                logger.error(e);
                throw new ShareProposalNotCreatedException(e);
            }

            // Move chunks to new container
            for (String chunkName : chunks) {
                try {
                    storageManager.copyChunk(sourceWorkspace, defaultWorkspace, chunkName);
                } catch (ObjectNotFoundException e) {
                    logger.error(String.format(
                            "Chunk %s not found in container %s. Could not migrate to container %s.", chunkName,
                            sourceWorkspace.getSwiftContainer(), defaultWorkspace.getSwiftContainer()), e);
                } catch (Exception e) {
                    logger.error(e);
                    throw new ShareProposalNotCreatedException(e);
                }
            }

            // delete workspace
            try {
                workspaceDAO.delete(sourceWorkspace.getId());
            } catch (DAOException e) {
                logger.error(e);
                throw new ShareProposalNotCreatedException(e);
            }

            // delete container from swift
            try {
                storageManager.deleteWorkspace(defaultWorkspace);
            } catch (Exception e) {
                logger.error(e);
                throw new ShareProposalNotCreatedException(e);
            }

        } else {

            for (User userToRemove : usersToRemove) {

                try {
                    workspaceDAO.deleteUser(userToRemove, sourceWorkspace);
                } catch (DAOException e) {
                    logger.error(e);
                    throw new ShareProposalNotCreatedException(e);
                }

                try {
                    storageManager.removeUserToWorkspace(user, userToRemove, sourceWorkspace);
                } catch (Exception e) {
                    logger.error(e);
                    throw new ShareProposalNotCreatedException(e);
                }
            }
        }
    }

    public List<UserWorkspace> doGetWorkspaceMembers(User user, Workspace workspace) throws InternalServerError {

		// TODO: check user permissions.
        List<UserWorkspace> members;
        try {
            members = workspaceDAO.getMembersById(workspace.getId());

        } catch (DAOException e) {
            logger.error(e);
            throw new InternalServerError(e);
        }

        if (members == null || members.isEmpty()) {
            throw new InternalServerError("No members found in workspace.");
        }

        return members;
    }

    public Connection getConnection() {
        return this.connection;
    }

    /*
     * Private functions
     */
    private void commitObject(SyncMetadata item, Workspace workspace, Device device) throws CommitWrongVersionNoParent,
            CommitWrongVersion, CommitExistantVersion, DAOException {

        Item serverItem = itemDao.findById(item.getId());

        // Check if this object already exists in the server.
        if (serverItem == null) {
            if (item.getVersion() == 1) {
                this.saveNewObject(item, workspace, device);
            } else {
                throw new CommitWrongVersionNoParent();
            }
            return;
        }

        // Check if the client version already exists in the server
        long serverVersion = serverItem.getLatestVersion();
        long clientVersion = item.getVersion();
        boolean existVersionInServer = (serverVersion >= clientVersion);

        if (existVersionInServer) {
            this.saveExistentVersion(serverItem, (ItemMetadata) item);
        } else {
            // Check if version is correct
            if (serverVersion + 1 == clientVersion) {
                this.saveNewVersion(item, serverItem, workspace, device);
            } else {
                throw new CommitWrongVersion("Invalid version.", serverItem);
            }
        }
    }

    private void saveNewObject(SyncMetadata metadata, Workspace workspace, Device device) throws DAOException {
        // Create workspace and parent instances
        Long parentId = metadata.getParentId();
        Item parent = null;
        if (parentId != null) {
            parent = itemDao.findById(parentId);
        }

        beginTransaction();

        try {
            // Get an item object from the provided metadata
            Item item = getItemFromMeta(metadata, parent, workspace);

            // Save the item to DB according to the expected metadata managed by the workspace
            if (workspace.isAbeEncrypted()) {
                // Get an ABE item object from a plain item and the provided ABE metadata
                ABEItem abeItem = getAbeItemFromMeta(metadata, item);
                // Insert ABE item to DB
                abeItemDao.put(abeItem);
                // set the global ID
                metadata.setId(abeItem.getId());
                item.setId(abeItem.getId());
            } else {
                // Insert item to DB
                itemDao.put(item);
                // set the global ID
                metadata.setId(item.getId());
            }

            // Get an item version object from provided metadata
            ItemVersion itemVersion = getItemVersionFromMeta(item, metadata, device);

            // Insert itemVersion
            itemVersionDao.add(itemVersion);

            // If no folder, create new chunks
            if (!metadata.isFolder()) {
                List<String> chunks = metadata.getChunks();
                this.createChunks(chunks, itemVersion);
            }

            commitTransaction();
        } catch (Exception e) {
            logger.error(e);
            rollbackTransaction();
        }
    }

    private Item getItemFromMeta(SyncMetadata meta, Item parent, Workspace workspace) throws ClassCastException {

        ItemMetadata metadata = (ItemMetadata) meta;

        Item item = new Item();
        item.setId(metadata.getId());
        item.setFilename(metadata.getFilename());
        item.setMimetype(metadata.getMimetype());
        item.setIsFolder(metadata.isFolder());
        item.setClientParentFileVersion(metadata.getParentVersion());

        item.setLatestVersion(metadata.getVersion());
        item.setWorkspace(workspace);
        item.setParent(parent);

        return item;
    }

    private ABEItem getAbeItemFromMeta(SyncMetadata meta, Item item) throws ClassCastException {

        ABEItem abeItem = new ABEItem(item);
        ABEItemMetadata metadata = (ABEItemMetadata) meta;

        abeItem.setAbeComponents(metadata.getAbeComponents());
        abeItem.setCipherSymKey(metadata.getCipherSymKey());

        return abeItem;
    }

    private ItemVersion getItemVersionFromMeta(Item item, SyncMetadata meta, Device device) {

        ItemMetadata metadata = (ItemMetadata) meta;

        ItemVersion objectVersion = new ItemVersion();
        objectVersion.setVersion(metadata.getVersion());
        objectVersion.setModifiedAt(metadata.getModifiedAt());
        objectVersion.setChecksum(metadata.getChecksum());
        objectVersion.setStatus(metadata.getStatus());
        objectVersion.setSize(metadata.getSize());

        objectVersion.setItem(item);
        objectVersion.setDevice(device);

        return objectVersion;
    }

    private void saveNewVersion(SyncMetadata metadata, Item serverItem, Workspace workspace, Device device)
            throws DAOException {

        beginTransaction();

        try {
            // Get an item version object from provided metadata
            ItemVersion itemVersion = getItemVersionFromMeta(serverItem, metadata, device);

            itemVersionDao.add(itemVersion);

            // If no folder, create new chunks
            if (!metadata.isFolder()) {
                List<String> chunks = metadata.getChunks();
                this.createChunks(chunks, itemVersion);
            }

            serverItem = updateServerItem(serverItem, metadata);

            // Update object latest version
            serverItem.setLatestVersion(metadata.getVersion());

            // Save the item to DB according to the expected metadata managed by the workspace
            if (workspace.isAbeEncrypted()) {
                // Get an ABE item object from a plain item and the provided ABE metadata
                ABEItem abeItem = getAbeItemFromMeta(metadata, serverItem);
                // Insert ABE item to DB
                abeItemDao.put(abeItem);
            } else {
                // Insert item to DB
                itemDao.put(serverItem);
            }

            commitTransaction();
        } catch (Exception e) {
            logger.error(e);
            rollbackTransaction();
        }
    }

    private Item updateServerItem(Item serverItem, SyncMetadata meta) throws DAOException {
        ItemMetadata metadata = (ItemMetadata) meta;
        // TODO To Test!!
        String status = metadata.getStatus();
        if (status.equals(Status.RENAMED.toString()) || status.equals(Status.MOVED.toString())
                || status.equals(Status.DELETED.toString())) {

            serverItem.setFilename(metadata.getFilename());

            Long parentFileId = metadata.getParentId();
            if (parentFileId == null) {
                serverItem.setClientParentFileVersion(null);
                serverItem.setParent(null);
            } else {
                serverItem.setClientParentFileVersion(metadata.getParentVersion());
                Item parent = itemDao.findById(parentFileId);
                serverItem.setParent(parent);
            }
        }
        return serverItem;
    }

    private void createChunks(List<String> chunksString, ItemVersion objectVersion) throws IllegalArgumentException,
            DAOException {
        if (chunksString != null) {
            if (chunksString.size() > 0) {
                List<Chunk> chunks = new ArrayList<Chunk>();
                int i = 0;

                for (String chunkName : chunksString) {
                    chunks.add(new Chunk(chunkName, i));
                    i++;
                }

                itemVersionDao.insertChunks(chunks, objectVersion.getId());
            }
        }
    }

    private void saveExistentVersion(Item serverObject, ItemMetadata clientMetadata) throws CommitWrongVersion,
            CommitExistantVersion, DAOException {

        ItemMetadata serverMetadata = this.getServerObjectVersion(serverObject, clientMetadata.getVersion());

        if (!clientMetadata.equals(serverMetadata)) {
            throw new CommitWrongVersion("Invalid version.", serverObject);
        }

        boolean lastVersion = (serverObject.getLatestVersion().equals(clientMetadata.getVersion()));

        if (!lastVersion) {
            throw new CommitExistantVersion("This version already exists.", serverObject, clientMetadata.getVersion());
        }
    }

    private ItemMetadata getCurrentServerVersion(Item serverObject) throws DAOException {
        return getServerObjectVersion(serverObject, serverObject.getLatestVersion());
    }

    private ItemMetadata getServerObjectVersion(Item serverObject, long requestedVersion) throws DAOException {

        ItemMetadata metadata = itemVersionDao.findByItemIdAndVersion(serverObject.getId(), requestedVersion);

        return metadata;
    }

    private void beginTransaction() throws DAOException {
        try {
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new DAOException(e);
        }
    }

    private void commitTransaction() throws DAOException {
        try {
            connection.commit();
            this.connection.setAutoCommit(true);
        } catch (SQLException e) {
            throw new DAOException(e);
        }
    }

    private void rollbackTransaction() throws DAOException {
        try {
            this.connection.rollback();
            this.connection.setAutoCommit(true);
        } catch (SQLException e) {
            throw new DAOException(e);
        }
    }

}
