package com.sercomm.openfire.plugin.websocket.v0;

import java.util.Base64;
import java.util.Collection;
import java.util.Locale;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;

import org.dom4j.Element;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;

import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.jivesoftware.openfire.Connection;
import org.jivesoftware.openfire.SessionManager;
import org.jivesoftware.openfire.SessionPacketRouter;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.auth.AuthFactory;
import org.jivesoftware.openfire.auth.AuthToken;
import org.jivesoftware.openfire.session.ClientSession;
import org.jivesoftware.openfire.session.ConnectionSettings;
import org.jivesoftware.openfire.session.LocalClientSession;
import org.jivesoftware.openfire.streammanagement.StreamManager;
import org.jivesoftware.openfire.user.UserManager;
import org.jivesoftware.util.JiveConstants;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.TaskEngine;
import org.xmpp.packet.IQ;
import org.xmpp.packet.Presence;

import com.sercomm.common.util.Algorithm;
import com.sercomm.commons.id.NameRule;
import com.sercomm.commons.util.Json;
import com.sercomm.commons.util.Log;
import com.sercomm.commons.util.XStringUtil;
import com.sercomm.openfire.plugin.DeviceManager;
import com.sercomm.openfire.plugin.EndUserManager;
import com.sercomm.openfire.plugin.OwnershipManager;
import com.sercomm.openfire.plugin.cache.DeviceCache;
import com.sercomm.openfire.plugin.component.DeviceComponent;
import com.sercomm.openfire.plugin.data.ubus.Identification;
import com.sercomm.openfire.plugin.define.DeviceType;
import com.sercomm.openfire.plugin.define.EndUserRole;
import com.sercomm.openfire.plugin.define.OwnershipType;
import com.sercomm.openfire.plugin.dispatcher.DeviceEnrollDispatcher;
import com.sercomm.openfire.plugin.util.ArgumentUtil;
import com.sercomm.openfire.plugin.websocket.v0.packet.Datagram;
import com.sercomm.openfire.plugin.websocket.v0.packet.ErrorCondition;
import com.sercomm.openfire.plugin.websocket.v0.packet.Function;
import com.sercomm.openfire.plugin.websocket.v0.packet.Type;

@WebSocket
public class DeviceWebSocket 
{
    public final static String SESSION_SERCOMM = "sercomm";
    public final static String SESSION_IDENTIFICATION = "sercomm.identification";
    public final static String SESSION_MUTUAL_AUTH = "sercomm.mutual.auth";
    public final static String SESSION_CLIENT_ADDR = "sercomm.client.addr";
    public final static String SESSION_CERT_DN = "sercomm.client.addr";
    public final static String SESSION_LAST_PING = "sercomm.last.ping";
    
    private final static AtomicLong ai = new AtomicLong(0L);
    
    private final Long id;
    private WebSocketConnection connection;
    private Session session;
    private LocalClientSession localClientSession;
    private SessionPacketRouter router;
    private TimerTask pingTask;

    private String serial = XStringUtil.BLANK;
    private String mac = XStringUtil.BLANK;
    private String modelName = XStringUtil.BLANK;
    
    private final Boolean mutualAuth;
    private final String clientAddress;
    private final String dnValue;

    public DeviceWebSocket(String mutualAuthValue, String clientAddress, String dnValue)
    {
        this.id = ai.incrementAndGet();
        if(null == mutualAuthValue || 0 != mutualAuthValue.compareToIgnoreCase(Boolean.TRUE.toString()))
        {
            this.mutualAuth = false;
        }
        else
        {
            this.mutualAuth = true;
        }

        this.clientAddress = clientAddress;
        this.dnValue = dnValue;
    }

    public void setLocalClientSession(LocalClientSession localClientSession)
    {
        this.localClientSession = localClientSession;
    }
    
    public LocalClientSession getLocalClientSession()
    {
        return this.localClientSession;
    }
    
    // WebSocket event handlers
    @OnWebSocketConnect
    public void onConnect(Session session)
    {
        Log.write().debug("({})", this.id);
        
        this.session = session;
        this.connection = new WebSocketConnection(this, session.getRemoteAddress());

        this.pingTask = new MonitorPingTask();
        TaskEngine.getInstance().schedule(
            this.pingTask, 
            JiveConstants.MINUTE, 
            JiveConstants.MINUTE);
    }

    @OnWebSocketClose
    public void onClose(int statusCode, String reason)
    {
        Log.write().debug("({},{},{})={},{}", this.id, this.serial, this.mac, statusCode, reason);
        this.close(StatusCode.NORMAL, reason);
    }

    @OnWebSocketMessage
    public void onTextMethod(String text)
    {
        try 
        {
            Datagram datagram = Datagram.parse(text);
            if(!isOpen()) 
            {
                Function function = Function.valueOf(ArgumentUtil.get(datagram.getArguments(), 0, XStringUtil.BLANK));
                if(null == function)
                {
                    sendError(
                        datagram.getId(), 
                        ErrorCondition.E_NOT_AUTHORIZED, 
                        "UNAUTHORIZED");
                    return;
                }
                
                switch(function)
                {
                    case F_AUTHENTICATE:
                        DeviceWebSocket.authenticate(
                            this,
                            datagram.getId(),
                            ArgumentUtil.get(datagram.getArguments(), 1, XStringUtil.BLANK),
                            ArgumentUtil.get(datagram.getArguments(), 2, XStringUtil.BLANK),
                            (Identification)ArgumentUtil.get(datagram.getArguments(), 3, Identification.class, new Identification()));
                        break;
                    case F_AUTHENTICATE_V2:
                        // perform authentication
                        DeviceWebSocket.authenticateV2(
                            this,
                            datagram.getId(),
                            ArgumentUtil.get(datagram.getArguments(), 1, XStringUtil.BLANK),
                            ArgumentUtil.get(datagram.getArguments(), 2, XStringUtil.BLANK),
                            ArgumentUtil.get(datagram.getArguments(), 3, XStringUtil.BLANK),
                            ArgumentUtil.get(datagram.getArguments(), 4, XStringUtil.BLANK),
                            ArgumentUtil.get(datagram.getArguments(), 5, XStringUtil.BLANK),
                            ArgumentUtil.get(datagram.getArguments(), 6, XStringUtil.BLANK));
                        break;
                    default:
                        sendError(
                            datagram.getId(), 
                            ErrorCondition.E_NOT_AUTHORIZED, 
                            "NOT AUTHORIZED YET");
                        break;
                }
            } 
            else 
            {
                this.process(this, datagram);
            }
        } 
        catch(Throwable t) 
        {
            Log.write().debug("FAILED TO PROCESS PACKET", t);
        } 
    }

    @OnWebSocketError
    public void onError(Throwable error)
    {
        try 
        {
            if(isOpen()) 
            {
                // send error message before closing
                this.sendError(
                    UUID.randomUUID().toString(), 
                    ErrorCondition.E_UNEXPECTED_CONDITION, 
                    error.getMessage());

                this.close(StatusCode.NORMAL, error.getMessage());
            }
        } 
        catch(Throwable t) 
        {
            Log.write().error("FAILED TO CLOSE WEBSOCKET", t);
        }
    }

    public boolean isOpen() 
    {
        return this.localClientSession != null && 
               this.session != null && this.session.isOpen();
    }

    public boolean isSecure() 
    {
        return this.session != null && this.session.isSecure();
    }

    public void close()
    {
        this.close(StatusCode.NORMAL, XStringUtil.BLANK);
    }
    
    public void close(int statusCode, String reason)
    {
        if(null != this.session)
        {
            this.session.close();
            this.session = null;
        }
        
        if(this.localClientSession != null) 
        {            
            this.localClientSession.close();
            SessionManager.getInstance().removeSession(this.localClientSession);
            
            this.localClientSession = null;
        }
        
        if(null != this.pingTask)
        {
            TaskEngine.getInstance().cancelScheduledTask(this.pingTask);
            this.pingTask = null;
        }
    }

    public void deliver(String jsonString)
    {
        if(isOpen())
        {
            try 
            {
                this.localClientSession.incrementServerPacketCount();
                this.session.getRemote().sendStringByFuture(jsonString);
            } 
            catch(Exception e) 
            {
                Log.write().error("Packet delivery failed; session: " + this.session, e);
            }
        } 
        else 
        {
            Log.write().warn("Failed to deliver packet; socket is closed:\n" + jsonString);
        }
    }

    public static boolean isCompressionEnabled() 
    {
        return JiveGlobals.getProperty(
                ConnectionSettings.Client.COMPRESSION_SETTINGS, Connection.CompressionPolicy.optional.toString())
                .equalsIgnoreCase(Connection.CompressionPolicy.optional.toString());
    }
    
    private boolean isStreamManagementAvailable() 
    {
        return true;
    }

    private void sendError(
            String id,
            ErrorCondition errorCondition, 
            String errorMessage)
    {
        if(null == this.session) return;
            
        Datagram packet = new Datagram();
        packet.setId(XStringUtil.isBlank(id) ? UUID.randomUUID().toString() : id);
        packet.setType(Type.T_ERROR);
        
        packet.getArguments().add(errorCondition.name());;
        packet.getArguments().add(errorMessage);
        
        this.session.getRemote().sendStringByFuture(packet.toString());
    }
    
    // task for monitoring ping status
    private final class MonitorPingTask extends TimerTask 
    {
        @Override
        public void run() 
        {
            String errorMessage = XStringUtil.BLANK;
            try
            {
                if(!isOpen()) 
                {
                    errorMessage = "SESSION IS NOT OPENED";
                    return;
                } 
                
                long lastPingTime = (Long)localClientSession.getSessionData(
                    SESSION_LAST_PING);
                long idleTime = System.currentTimeMillis() - lastPingTime;
                if(idleTime <= JiveConstants.MINUTE)
                {
                    return;
                }
                
                // ping idle timeout
                errorMessage = "PING IDLE TIMEOUT";
            }
            catch(Throwable t)
            {
                Log.write().error(t.getMessage(), t);
            }
            finally
            {
                Log.write().debug("({},{},{})={}", id, serial, mac, errorMessage);
                if(!XStringUtil.isBlank(errorMessage))
                {
                    close(StatusCode.NORMAL, errorMessage);
                }
            }
        }
    }
    
    private static void authenticate(
            DeviceWebSocket deviceWebSocket,
            String requestId,
            String credential,
            String nonce,
            Identification identification)
    throws Throwable
    {
        String errorMessage = XStringUtil.BLANK;
        try
        {
            do
            {
                if(XStringUtil.isBlank(requestId))
                {
                    errorMessage = "ID OF DATAGRAM CANNOT BE BLANK";
                    break;
                }
                
                if(null == identification)
                {
                    errorMessage = "IDENTIFICATION CANNOT BE BLANK";
                    break;
                }

                String serial = identification.SerialNumber;
                String mac = identification.MACAddress;
                String modelName = identification.ModelName;
                
                if(XStringUtil.isBlank(serial) ||
                   XStringUtil.isBlank(mac))
                {
                    errorMessage = "CREDENTIAL CANNOT BE BLANK";
                    break;
                }
                mac = mac.replaceAll(":", "");
                
                if(XStringUtil.isBlank(credential) ||
                   XStringUtil.isBlank(nonce))
                {
                    errorMessage = "CREDENTIAL CANNOT BE BLANK";
                    break;
                }
                
                final String nodeName = NameRule.formatDeviceName(serial, mac);
                Long creationTime = 0L;
                
                if(false == UserManager.getInstance().isRegisteredUser(nodeName))
                {
                    // create its account automatically
                    UserManager.getInstance().createUser(
                        nodeName, 
                        Algorithm.md5(mac), 
                        XStringUtil.BLANK, 
                        XStringUtil.BLANK);
                    
                    creationTime = System.currentTimeMillis();
                    
                    // create its end-user
                    String userId = serial.toUpperCase();
                    if(false == EndUserManager.getInstance().isRegisteredUser(userId))
                    {
                        String password = mac.toUpperCase();
                        EndUserManager.getInstance().createUser(userId, password, EndUserRole.MEMBER);

                        // pairing user & device automatically
                        OwnershipManager.getInstance().updateOwnership(serial, mac, userId, OwnershipType.OWNED);
                    }
                }

                final String password = AuthFactory.getPassword(nodeName);
                if(0 != credential.compareToIgnoreCase(Algorithm.md5(password + nonce)))
                {
                    errorMessage = "INVALID CREDENTIAL";
                    break;
                }

                // update device properties
                Lock locker = DeviceManager.getInstance().getLock(serial, mac);
                try
                {
                    locker.lock();
                    
                    DeviceCache deviceCache = DeviceManager.getInstance().getDeviceCache(serial, mac);
                    if(0L != creationTime)
                    {
                        deviceCache.setPlatform("P_NEUTRAL");
                        deviceCache.setDeviceType(DeviceType.D_CPE);
                        deviceCache.setModelName(modelName);
                        deviceCache.setCreationTime(creationTime);
                    }
                    deviceCache.setFirmwareVersion(identification.FirmwareVersion);
                    
                    deviceCache.flush();
                }
                finally
                {
                    locker.unlock();
                }

                // check if there is any existed session
                Collection<ClientSession> sessions = SessionManager.getInstance().getSessions(nodeName);
                for(ClientSession session : sessions)
                {
                    // close the existed session
                    session.close();
                }
                
                // valid stream; initialize/create new session
                deviceWebSocket.localClientSession = SessionManager.getInstance().createClientSession(
                    deviceWebSocket.connection, 
                    Locale.forLanguageTag("en"));
                
                deviceWebSocket.serial = serial;
                deviceWebSocket.mac = mac;
                deviceWebSocket.modelName = modelName;
                deviceWebSocket.localClientSession.setSessionData(
                    SESSION_SERCOMM, 
                    Boolean.TRUE);
                deviceWebSocket.localClientSession.setSessionData(
                    SESSION_MUTUAL_AUTH, 
                    deviceWebSocket.mutualAuth);
                deviceWebSocket.localClientSession.setSessionData(
                    SESSION_CLIENT_ADDR, 
                    deviceWebSocket.clientAddress);
                deviceWebSocket.localClientSession.setSessionData(
                    SESSION_CERT_DN, 
                    deviceWebSocket.dnValue);
                deviceWebSocket.localClientSession.setSessionData(
                    SESSION_LAST_PING, 
                    System.currentTimeMillis());
                deviceWebSocket.localClientSession.setSessionData(
                    SESSION_IDENTIFICATION, 
                    identification);

                deviceWebSocket.localClientSession.setAuthToken(new AuthToken(nodeName, false), identification.ModelName);
                
                Presence presence = new Presence();
                presence.setType(null);
                deviceWebSocket.localClientSession.setPresence(presence);                
            }
            while(false);
            
            if(null == deviceWebSocket.localClientSession)
            {
                deviceWebSocket.sendError(
                    requestId, 
                    ErrorCondition.E_NOT_AUTHORIZED, 
                    errorMessage);
            }
            else
            {
                if(null == deviceWebSocket.session) return;
                
                Datagram datagram = Datagram.make(requestId, Type.T_RESULT);         
                deviceWebSocket.session.getRemote().sendStringByFuture(datagram.toString());
            }
        }
        catch(Throwable t)
        {
            errorMessage = t.getMessage();
            throw t;
        }
        finally
        {
            Log.write().info("({},{},{},{},{})={}",
                deviceWebSocket.id,
                requestId,
                credential,
                nonce,
                Json.build(identification),
                errorMessage);
        }
    }

    private static void authenticateV2(
            DeviceWebSocket deviceWebSocket,
            String requestId,
            String credential,
            String nonce,
            String serial,
            String mac,
            String modelName,
            String firmwareVersion)
    throws Throwable
    {
        String errorMessage = XStringUtil.BLANK;
        try
        {
            do
            {
                if(XStringUtil.isBlank(requestId))
                {
                    errorMessage = "ID OF DATAGRAM CANNOT BE BLANK";
                    break;
                }
                
                if(XStringUtil.isBlank(credential) ||
                   XStringUtil.isBlank(nonce))
                {
                    errorMessage = "CREDENTIAL CANNOT BE BLANK";
                    break;
                }

                if(XStringUtil.isBlank(serial))
                {
                    errorMessage = "'serial' CANNOT BE BLANK";
                    break;
                }

                if(XStringUtil.isBlank(mac))
                {
                    errorMessage = "'mac' CANNOT BE BLANK";
                    break;
                }

                if(XStringUtil.isBlank(modelName))
                {
                    errorMessage = "'modelName' CANNOT BE BLANK";
                    break;
                }

                mac = mac.replaceAll(":", "").toUpperCase();

                final String nodeName = NameRule.formatDeviceName(serial, mac);
                Long creationTime = 0L;

                boolean isEnrolled = false;
                if(false == UserManager.getInstance().isRegisteredUser(nodeName))
                {
                    // create its account automatically
                    UserManager.getInstance().createUser(
                        nodeName, 
                        Algorithm.md5(mac), 
                        XStringUtil.BLANK, 
                        XStringUtil.BLANK);
                    
                    creationTime = System.currentTimeMillis();  
                    
                    // create its end-user
                    String userId = serial.toUpperCase();
                    if(false == EndUserManager.getInstance().isRegisteredUser(userId))
                    {
                        String password = mac.toUpperCase();
                        EndUserManager.getInstance().createUser(userId, password, EndUserRole.MEMBER);

                        // pairing user & device automatically
                        OwnershipManager.getInstance().updateOwnership(serial, mac, userId, OwnershipType.OWNED);
                    }
                    
                    isEnrolled = true;
                }

                final String password = AuthFactory.getPassword(nodeName);
                if(0 != credential.compareToIgnoreCase(Algorithm.md5(password + nonce)))
                {
                    errorMessage = "INVALID CREDENTIAL";
                    break;
                }

                // update device properties
                Lock locker = DeviceManager.getInstance().getLock(serial, mac);
                try
                {
                    locker.lock();
                    
                    DeviceCache deviceCache = DeviceManager.getInstance().getDeviceCache(serial, mac);
                    if(0L != creationTime)
                    {
                        deviceCache.setPlatform("P_NEUTRAL");
                        deviceCache.setDeviceType(DeviceType.D_CPE);
                        deviceCache.setCreationTime(creationTime);
                    }
                    
                    // model name can be updated dynamically
                    deviceCache.setModelName(modelName);
                    deviceCache.setFirmwareVersion(firmwareVersion);
                    
                    deviceCache.flush();
                }
                finally
                {
                    locker.unlock();
                }

                if(isEnrolled)
                {
                    DeviceEnrollDispatcher.dispatchEnrolled(serial, mac, modelName);
                }
                
                // check if there is any existed session
                Collection<ClientSession> sessions = SessionManager.getInstance().getSessions(nodeName);
                for(ClientSession session : sessions)
                {
                    // close the existed session
                    session.close();
                }
                
                // valid stream; initialize/create new session
                deviceWebSocket.localClientSession = SessionManager.getInstance().createClientSession(
                    deviceWebSocket.connection, 
                    Locale.forLanguageTag("en"));
                
                deviceWebSocket.serial = serial;
                deviceWebSocket.mac = mac;
                deviceWebSocket.modelName = modelName;
                deviceWebSocket.localClientSession.setSessionData(
                    SESSION_SERCOMM, 
                    Boolean.TRUE);
                deviceWebSocket.localClientSession.setSessionData(
                    SESSION_MUTUAL_AUTH, 
                    deviceWebSocket.mutualAuth);
                deviceWebSocket.localClientSession.setSessionData(
                    SESSION_CLIENT_ADDR, 
                    deviceWebSocket.clientAddress);
                deviceWebSocket.localClientSession.setSessionData(
                    SESSION_CERT_DN, 
                    deviceWebSocket.dnValue);
                deviceWebSocket.localClientSession.setSessionData(
                    SESSION_LAST_PING, 
                    System.currentTimeMillis());

                deviceWebSocket.localClientSession.setAuthToken(new AuthToken(nodeName, false), modelName);
                
                Presence presence = new Presence();
                presence.setType(null);
                deviceWebSocket.localClientSession.setPresence(presence);
            }
            while(false);
            
            if(null == deviceWebSocket.localClientSession)
            {
                deviceWebSocket.sendError(
                    requestId, 
                    ErrorCondition.E_NOT_AUTHORIZED, 
                    errorMessage);
            }
            else
            {
                if(null == deviceWebSocket.session) return;
                
                Datagram datagram = Datagram.make(requestId, Type.T_RESULT);         
                deviceWebSocket.session.getRemote().sendStringByFuture(datagram.toString());
            }
        }
        catch(Throwable t)
        {
            errorMessage = t.getMessage();
            throw t;
        }
        finally
        {
            Log.write().info("({},{},{},{},{},{},{},{})={}",
                deviceWebSocket.id,
                requestId,
                credential,
                nonce,
                serial,
                mac,
                modelName,
                firmwareVersion,
                errorMessage);
        }
    }

    private void process(
            DeviceWebSocket deviceWebSocket,
            Datagram datagram) 
    {
        final String nodeName = NameRule.formatDeviceName(this.serial, this.mac);
        try 
        {
            if(this.router == null) 
            {
                if(isStreamManagementAvailable()) 
                {
                    this.router = new StreamManagementPacketRouter(this.localClientSession);
                } 
                else 
                {
                    // fall back for older installations
                    this.router = new SessionPacketRouter(this.localClientSession);
                }
            }

            Type type = datagram.getType();
            switch(type)
            {
                case T_REQUEST:
                    {
                        // encapsulate packet to XMPP stanza
                        IQ requestIQ = new IQ();
                        requestIQ.setID(datagram.getId());
                        requestIQ.setType(org.xmpp.packet.IQ.Type.set);
                        requestIQ.setFrom(XMPPServer.getInstance().createJID(nodeName, this.modelName));
                        requestIQ.setTo(DeviceComponent.NAME + "." + XMPPServer.getInstance().getServerInfo().getXMPPDomain());
                        
                        Element elmRoot = requestIQ.setChildElement(
                            DeviceComponent.ELM_ROOT, 
                            DeviceComponent.NAMESPACE);
                        elmRoot.addAttribute(DeviceComponent.ATT_TYPE, datagram.getType().name());
                        Element elmArguments = elmRoot.addElement(DeviceComponent.ELM_ARGUMENTS);
                        elmArguments.setText(Base64.getEncoder().encodeToString(Json.build(datagram.getArguments()).getBytes()));
                        
                        // route the stanza to "DeviceComponent"
                        this.router.route(requestIQ);                    
                    }
                    break;
                case T_RESULT:
                case T_ERROR:
                    {
                        // encapsulate packet to XMPP stanza
                        IQ requestIQ = new IQ();
                        requestIQ.setID(datagram.getId());
                        requestIQ.setType(org.xmpp.packet.IQ.Type.result);
                        requestIQ.setFrom(XMPPServer.getInstance().createJID(nodeName, this.modelName));
                        requestIQ.setTo(XMPPServer.getInstance().getServerInfo().getXMPPDomain());
                        
                        Element elmRoot = requestIQ.setChildElement(
                            DeviceComponent.ELM_ROOT, 
                            DeviceComponent.NAMESPACE);
                        elmRoot.addAttribute(DeviceComponent.ATT_TYPE, datagram.getType().name());
                        Element elmArguments = elmRoot.addElement(DeviceComponent.ELM_ARGUMENTS);
                        elmArguments.setText(Base64.getEncoder().encodeToString(Json.build(datagram.getArguments()).getBytes()));
                        
                        // route the stanza to somewhere sent the datagram with specific ID to device
                        this.router.route(requestIQ);                    
                    }
                    break;
                default:
                    sendError(
                        datagram.getId(),
                        ErrorCondition.E_BAD_REQUEST,
                        "INVALID TYPE VALUE: " + (null != type ? type.name() : XStringUtil.BLANK));
                    break;
            }            
        }
        catch(Throwable t)
        {
            Log.write().error(t.getMessage(), t);
        }
    }    
}
