package org.red5.server.net.rtmp;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;

import org.red5.io.object.StreamAction;
import org.red5.logging.Red5LoggerFactory;
import org.red5.server.api.IConnection.Encoding;
import org.red5.server.api.IContext;
import org.red5.server.api.IServer;
import org.red5.server.api.Red5;
import org.red5.server.api.scope.IBroadcastScope;
import org.red5.server.api.scope.IGlobalScope;
import org.red5.server.api.scope.IScope;
import org.red5.server.api.scope.IScopeHandler;
import org.red5.server.api.service.IPendingServiceCall;
import org.red5.server.api.service.IServiceCall;
import org.red5.server.api.so.ISharedObject;
import org.red5.server.api.so.ISharedObjectSecurity;
import org.red5.server.api.so.ISharedObjectSecurityService;
import org.red5.server.api.so.ISharedObjectService;
import org.red5.server.api.stream.IClientBroadcastStream;
import org.red5.server.api.stream.IClientStream;
import org.red5.server.api.stream.IStreamService;
import org.red5.server.exception.ClientRejectedException;
import org.red5.server.exception.ScopeNotFoundException;
import org.red5.server.exception.ScopeShuttingDownException;
import org.red5.server.messaging.IConsumer;
import org.red5.server.messaging.OOBControlMessage;
import org.red5.server.net.ICommand;
import org.red5.server.net.rtmp.codec.RTMP;
import org.red5.server.net.rtmp.event.ChunkSize;
import org.red5.server.net.rtmp.event.Invoke;
import org.red5.server.net.rtmp.event.Ping;
import org.red5.server.net.rtmp.event.SetBuffer;
import org.red5.server.net.rtmp.event.StreamActionEvent;
import org.red5.server.net.rtmp.message.Header;
import org.red5.server.net.rtmp.status.Status;
import org.red5.server.net.rtmp.status.StatusObject;
import org.red5.server.net.rtmp.status.StatusObjectService;
import org.red5.server.service.Call;
import org.red5.server.so.ISharedObjectEvent;
import org.red5.server.so.SharedObjectEvent;
import org.red5.server.so.SharedObjectMessage;
import org.red5.server.so.SharedObjectService;
import org.red5.server.stream.StreamService;
import org.red5.server.util.ScopeUtils;
import org.slf4j.Logger;


public class RTMPOriginHandler extends RTMPHandler {
    /**
     * Remoting call invocation handler.
     * 
     * @param conn
     *            RTMP connection
     * @param call
     *            Service call
     * @param service
     *            Server-side service object
     * @return true if the call was performed, otherwise false
     */
    private boolean invokeCall(RTMPConnection conn, IServiceCall call, Object service){
            //System.out.println("===================D5InvokeCall==================");
            final IScope scope = conn.getScope();
            final IContext context = scope.getContext();
            if (log.isTraceEnabled()) {
                log.trace("Scope: {} context: {} service: {}", scope, context, service);
            }

            return context.getServiceInvoker().invoke(call, service);
    }

    @Override
    protected void onCommand(RTMPConnection conn, Channel channel, Header source, ICommand command) {
        //System.out.println("===================onCommand in Channel #"+source.getChannelId());
        log.debug("onCommand {}", command);
        // incoming transaction id (response to 'connect' must be == 1)
        final int transId = command.getTransactionId();
        // get the call
        final IServiceCall call = command.getCall();
        if (log.isTraceEnabled()) {
            log.trace("call: {}", call);
        }
        // get the method name
        final String action = call.getServiceMethodName();
        // If it's a callback for server remote call then pass it over to callbacks handler and return
        if ("_result".equals(action) || "_error".equals(action)) {
            handlePendingCallResult(conn, (Invoke) command);
            return;
        }
        boolean disconnectOnReturn = false;
        // "connected" here means that there is a scope associated with the connection (post-"connect")
        boolean connected = conn.isConnected();
        if (connected) {
            // If this is not a service call then handle connection...
            if (call.getServiceName() == null) {
                StreamAction streamAction = StreamAction.getEnum(action);
                if (log.isDebugEnabled()) {
                    log.debug("Stream action: {}", streamAction.toString());
                }
                // TODO change this to an application scope parameter and / or change to the listener pattern
                if (isDispatchStreamActions()) {
                    // pass the stream action event to the handler
                    try {
                        conn.getScope().getHandler().handleEvent(new StreamActionEvent(streamAction));
                    } catch (Exception ex) {
                        log.warn("Exception passing stream action: {} to the scope handler", streamAction, ex);
                    }
                }
                //if the "stream" action is not predefined a custom type will be returned
                switch (streamAction) {
                    case DISCONNECT:
                        conn.close();
                        break;
                    case CREATE_STREAM:
                    case INIT_STREAM:
                    case CLOSE_STREAM:
                    case RELEASE_STREAM:
                    case DELETE_STREAM:
                    case PUBLISH:
                    case PLAY:
                    case PLAY2:
                    case SEEK:
                    case PAUSE:
                    case PAUSE_RAW:
                    case RECEIVE_VIDEO:
                    case RECEIVE_AUDIO:
                        IStreamService streamService = (IStreamService) ScopeUtils.getScopeService(conn.getScope(), IStreamService.class, StreamService.class);
                        try {
                            log.warn("Invoking {} from {} with service: {}", new Object[] { call, conn.getSessionId(), streamService });
                            if (invokeCall(conn, call, streamService)) {
                                log.debug("Stream service invoke {} success", action);
                            } else {
                                Status status = getStatus(NS_INVALID_ARGUMENT).asStatus();
                                status.setDescription(String.format("Failed to %s (stream id: %d)", action, source.getStreamId()));
                                channel.sendStatus(status);
                            }
                        } catch (Throwable err) {
                            log.error("Error while invoking {} on stream service. {}", action, err);
                            Status status = getStatus(NS_FAILED).asStatus();
                            status.setDescription(String.format("Error while invoking %s (stream id: %d)", action, source.getStreamId()));
                            status.setDetails(err.getMessage());
                            channel.sendStatus(status);
                        }
                        break;
                    default:
                        log.debug("Defaulting to invoke for: {}", action);
                        invokeCall(conn, call);
                }
            } else {
                // handle service calls
                invokeCall(conn, call);
            }
        } else if (StreamAction.CONNECT.equals(action)) {
            // Handle connection
            log.debug("connect - transaction id: {}", transId);
            // Get parameters passed from client to NetConnection#connection
            final Map<String, Object> params = command.getConnectionParams();
            // Get hostname
            String host = getHostname((String) params.get("tcUrl"));
            // app name as path, but without query string if there is one
            String path = (String) params.get("app");
            if (path.indexOf("?") != -1) {
                int idx = path.indexOf("?");
                params.put("queryString", path.substring(idx));
                path = path.substring(0, idx);
            }
            params.put("path", path);
            // connection setup
            conn.setup(host, path, params);
            try {
                // Lookup server scope when connected using host and application name
                IGlobalScope global = server.lookupGlobal(host, path);
                log.trace("Global lookup result: {}", global);
                if (global != null) {
                    final IContext context = global.getContext();
                    IScope scope = null;
                    try {
                        // TODO optimize this to use Scope instead of Context
                        scope = context.resolveScope(global, path);
                        if (scope != null) {
                            if (log.isDebugEnabled()) {
                                log.debug("Connecting to: {}", scope.getName());
                                log.debug("Conn {}, scope {}, call {} args {}", new Object[] { conn, scope, call, call.getArguments() });
                            }
                            // if scope connection is allowed
                            if (scope.isConnectionAllowed(conn)) {
                                // connections connect result
                                boolean connectSuccess;
                                try {
                                    if (call.getArguments() != null) {
                                        connectSuccess = conn.connect(scope, call.getArguments());
                                    } else {
                                        connectSuccess = conn.connect(scope);
                                    }
                                    if (connectSuccess) {
                                        log.debug("Connected - {}", conn.getClient());
                                        call.setStatus(Call.STATUS_SUCCESS_RESULT);
                                        if (call instanceof IPendingServiceCall) {
                                            IPendingServiceCall pc = (IPendingServiceCall) call;
                                            //send fmsver and capabilities
                                            StatusObject result = getStatus(NC_CONNECT_SUCCESS);
                                            result.setAdditional("fmsVer", Red5.getFMSVersion());
                                            result.setAdditional("capabilities", Red5.getCapabilities());
                                            result.setAdditional("mode", Integer.valueOf(1));
                                            result.setAdditional("data", Red5.getDataVersion());
                                            pc.setResult(result);
                                        }
                                        // Measure initial round-trip time after connecting
                                        conn.ping(new Ping(Ping.STREAM_BEGIN, 0, -1));
                                    } else {
                                        log.debug("Connect failed");
                                        call.setStatus(Call.STATUS_ACCESS_DENIED);
                                        if (call instanceof IPendingServiceCall) {
                                            IPendingServiceCall pc = (IPendingServiceCall) call;
                                            pc.setResult(getStatus(NC_CONNECT_REJECTED));
                                        }
                                        disconnectOnReturn = true;
                                    }
                                } catch (ClientRejectedException rejected) {
                                    log.debug("Connect rejected");
                                    call.setStatus(Call.STATUS_ACCESS_DENIED);
                                    if (call instanceof IPendingServiceCall) {
                                        IPendingServiceCall pc = (IPendingServiceCall) call;
                                        StatusObject status = getStatus(NC_CONNECT_REJECTED);
                                        Object reason = rejected.getReason();
                                        if (reason != null) {
                                            status.setApplication(reason);
                                            //should we set description?
                                            status.setDescription(reason.toString());
                                        }
                                        pc.setResult(status);
                                    }
                                    disconnectOnReturn = true;
                                }
                            } else {
                                // connection to specified scope is not allowed
                                log.debug("Connect to specified scope is not allowed");
                                call.setStatus(Call.STATUS_ACCESS_DENIED);
                                if (call instanceof IPendingServiceCall) {
                                    IPendingServiceCall pc = (IPendingServiceCall) call;
                                    StatusObject status = getStatus(NC_CONNECT_REJECTED);
                                    status.setDescription(String.format("Connection to '%s' denied.", path));
                                    pc.setResult(status);
                                }
                                disconnectOnReturn = true;
                            }
                        }
                    } catch (ScopeNotFoundException err) {
                        log.warn("Scope not found", err);
                        call.setStatus(Call.STATUS_SERVICE_NOT_FOUND);
                        if (call instanceof IPendingServiceCall) {
                            StatusObject status = getStatus(NC_CONNECT_REJECTED);
                            status.setDescription(String.format("No scope '%s' on this server.", path));
                            ((IPendingServiceCall) call).setResult(status);
                        }
                        log.info("Scope {} not found on {}", path, host);
                        disconnectOnReturn = true;
                    } catch (ScopeShuttingDownException err) {
                        log.warn("Scope shutting down", err);
                        call.setStatus(Call.STATUS_APP_SHUTTING_DOWN);
                        if (call instanceof IPendingServiceCall) {
                            StatusObject status = getStatus(NC_CONNECT_APPSHUTDOWN);
                            status.setDescription(String.format("Application at '%s' is currently shutting down.", path));
                            ((IPendingServiceCall) call).setResult(status);
                        }
                        log.info("Application at {} currently shutting down on {}", path, host);
                        disconnectOnReturn = true;
                    }
                } else {
                    log.warn("Scope {} not found", path);
                    call.setStatus(Call.STATUS_SERVICE_NOT_FOUND);
                    if (call instanceof IPendingServiceCall) {
                        StatusObject status = getStatus(NC_CONNECT_INVALID_APPLICATION);
                        status.setDescription(String.format("No scope '%s' on this server.", path));
                        ((IPendingServiceCall) call).setResult(status);
                    }
                    log.info("No application scope found for {} on host {}", path, host);
                    disconnectOnReturn = true;
                }
            } catch (RuntimeException e) {
                call.setStatus(Call.STATUS_GENERAL_EXCEPTION);
                if (call instanceof IPendingServiceCall) {
                    IPendingServiceCall pc = (IPendingServiceCall) call;
                    pc.setResult(getStatus(NC_CONNECT_FAILED));
                }
                log.error("Error connecting {}", e);
                disconnectOnReturn = true;
            }
            // Evaluate request for AMF3 encoding
            if (new Double(3d).equals(params.get("objectEncoding"))) {
                if (call instanceof IPendingServiceCall) {
                    Object pcResult = ((IPendingServiceCall) call).getResult();
                    Map<String, Object> result;
                    if (pcResult instanceof Map) {
                        result = (Map<String, Object>) pcResult;
                        result.put("objectEncoding", 3);
                    } else if (pcResult instanceof StatusObject) {
                        result = new HashMap<>();
                        StatusObject status = (StatusObject) pcResult;
                        result.put("code", status.getCode());
                        result.put("description", status.getDescription());
                        result.put("application", status.getApplication());
                        result.put("level", status.getLevel());
                        result.put("objectEncoding", 3);
                        ((IPendingServiceCall) call).setResult(result);
                    }
                }
                conn.getState().setEncoding(Encoding.AMF3);
            }
        } else {
            // not connected and attempting to send an invoke
            log.warn("Not connected, closing connection");
            conn.close();
        }
        
        if (command instanceof Invoke) {
            if (log.isDebugEnabled()) {
                log.debug("Command type Invoke");
            }
            if ((source.getStreamId().intValue() != 0) && (call.getStatus() == Call.STATUS_SUCCESS_VOID || call.getStatus() == Call.STATUS_SUCCESS_NULL)) {
                // This fixes a bug in the FP on Intel Macs.
                log.debug("Method does not have return value, do not reply");
                return;
            }
            boolean sendResult = true;
            if (call instanceof IPendingServiceCall) {
                IPendingServiceCall psc = (IPendingServiceCall) call;
                Object result = psc.getResult();
                if (result instanceof DeferredResult) {
                    // Remember the deferred result to be sent later
                    DeferredResult dr = (DeferredResult) result;
                    dr.setServiceCall(psc);
                    dr.setChannel(channel);
                    dr.setTransactionId(transId);
                    conn.registerDeferredResult(dr);
                    sendResult = false;
                }
            }
            if (sendResult) {
                // The client expects a result for the method call
                Invoke reply = new Invoke();
                reply.setCall(call);
                reply.setTransactionId(transId);
                channel.write(reply);
                //System.out.println("[D5Power] back to client============"+reply);
                if (disconnectOnReturn) {
                    log.debug("Close connection due to connect handling exception: {}", conn.getSessionId());
                    conn.close();
                }
            }
        } else if (log.isDebugEnabled()) {
            log.debug("Command type: {}", command.getClass().getName());
        }
    }
}