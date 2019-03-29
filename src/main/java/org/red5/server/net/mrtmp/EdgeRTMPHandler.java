/*
 * RED5 Open Source Flash Server - http://code.google.com/p/red5/
 * 
 * Copyright 2006-2012 by respective authors (see below). All rights reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.red5.server.net.mrtmp;

import java.util.HashMap;
import java.util.Map;

import org.apache.mina.core.buffer.IoBuffer;
import org.red5.io.object.StreamAction;
import org.red5.server.Server;
import org.red5.server.api.IContext;
import org.red5.server.api.Red5;
import org.red5.server.api.IConnection.Encoding;
import org.red5.server.api.scope.IGlobalScope;
import org.red5.server.api.scope.IScope;
import org.red5.server.api.service.IPendingServiceCall;
import org.red5.server.api.service.IServiceCall;
import org.red5.server.exception.ClientRejectedException;
import org.red5.server.exception.ScopeNotFoundException;
import org.red5.server.exception.ScopeShuttingDownException;
import org.red5.server.net.ICommand;
import org.red5.server.net.rtmp.Channel;
import org.red5.server.net.rtmp.DeferredResult;
import org.red5.server.net.rtmp.RTMPConnection;
import org.red5.server.net.rtmp.RTMPHandler;
import org.red5.server.net.rtmp.codec.RTMP;
import org.red5.server.net.rtmp.event.BytesRead;
import org.red5.server.net.rtmp.event.IRTMPEvent;
import org.red5.server.net.rtmp.event.Invoke;
import org.red5.server.net.rtmp.event.Ping;
import org.red5.server.net.rtmp.event.Unknown;
import org.red5.server.net.rtmp.message.Header;
import org.red5.server.net.rtmp.message.Packet;
import org.red5.server.net.rtmp.status.StatusObject;
import org.red5.server.scope.Scope;
import org.red5.server.service.Call;

public class EdgeRTMPHandler extends RTMPHandler {

	private IMRTMPManager mrtmpManager;

	public void setMRTMPManager(IMRTMPManager mrtmpManager) {
		this.mrtmpManager = mrtmpManager;
	}

	@Override
	public void messageReceived(RTMPConnection conn, Packet packet) throws Exception {
		//System.out.println("[D5Power] EdgeRTMPHandler recived message,client id "+conn.getId()+".Header:"+packet.getHeader());
//		RTMPConnection conn = (RTMPConnection) session.getAttribute(RTMPConnection.RTMP_CONNECTION_KEY);
//		RTMP state = (RTMP) session.getAttribute(ProtocolState.SESSION_KEY);
		RTMP state = conn.getState();
		IRTMPEvent message = null;
//		final Packet packet = (Packet) in;
		message = packet.getMessage();
		final Header header = packet.getHeader();
		final Channel channel = conn.getChannel(header.getChannelId());

		// Increase number of received messages
		conn.messageReceived();

		//System.out.println("@@@@============EdgeRtmpHandle===messageReceived @================================ S2");

		if (header.getDataType() == TYPE_BYTES_READ) {
			// TODO need to sync the bytes read on edge and origin
			onStreamBytesRead(conn, channel, header, (BytesRead) message);
		}

		if (header.getDataType() == TYPE_INVOKE) {
			final IServiceCall call = ((Invoke) message).getCall();
			final String action = call.getServiceMethodName();

			//System.out.println(action+"@@@@================================"+call.getServiceName()+'@'+conn.isConnected());
			if (call.getServiceName() == null && !conn.isConnected() && action.equals("connect")) {
				//System.out.println("[D5Power] Send client connect event to origin,query client is "+conn.getId());
				handleConnect(conn, channel, header, (Invoke) message, (RTMP) state);
				return;
			}
		}

		switch (header.getDataType()) {
			case TYPE_CHUNK_SIZE:
			case TYPE_INVOKE:
			case TYPE_FLEX_MESSAGE:
			case TYPE_NOTIFY:
			case TYPE_AUDIO_DATA:
			case TYPE_VIDEO_DATA:
			case TYPE_FLEX_SHARED_OBJECT:
			case TYPE_FLEX_STREAM_SEND:
			case TYPE_SHARED_OBJECT:
			case TYPE_BYTES_READ:
				//System.out.println("[D5Power] Send forward to origin "+conn.getId()+" ,streamId is "+packet.getHeader().getStreamId());
				forwardPacket(conn, packet);
				break;
			case TYPE_PING:
				onPing(conn, channel, header, (Ping) message);
				break;

			default:
				if (log.isDebugEnabled()) {
					log.debug("Unknown type: {}", header.getDataType());
				}
		}
		if (message instanceof Unknown) {
			log.info(message.toString());
		}
		if (message != null) {
			message.release();
		}
	}

	public void messageSent(RTMPConnection conn, Object message) {
		//System.out.println("@@@=============Send message to flash ==========="+conn.getSessionId());
		if (message instanceof IoBuffer) {
			return;
		}
		// Increase number of sent messages
		conn.messageSent((Packet) message);
	}

	/**
	 * Pass through all Ping events to origin except ping/pong
	 */
	protected void onPing(RTMPConnection conn, Channel channel, Header source, Ping ping) {
		switch (ping.getEventType()) {
			case Ping.PONG_SERVER:
				// This is the response to an IConnection.ping request
				conn.pingReceived(ping);
				break;
			default:
				// forward other to origin
				Packet p = new Packet(source);
				p.setMessage(ping);
				forwardPacket(conn, p);
				break;
		}
	}

	/** {@inheritDoc} */
    @SuppressWarnings({ "unchecked" })
	@Override
	/**
	 * 本方法仅用于测试连接的实现原理
	 */
    protected void onCommand(RTMPConnection conn, Channel channel, Header source, ICommand command) {
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
                        scope = new Scope();
                        if (scope != null) {
                            if (log.isDebugEnabled()) {
                                log.debug("Connecting to: {}", scope.getName());
                                log.debug("Conn {}, scope {}, call {} args {}", new Object[] { conn, scope, call, call.getArguments() });
                            }
                            // if scope connection is allowed
                            if (scope.isConnectionAllowed(conn)) {
                                // connections connect result
                                boolean connectSuccess = true;
                                try {
									((EdgeRTMPMinaConnection)conn).connected(((Server)server));
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
                if (disconnectOnReturn) {
                    log.debug("Close connection due to connect handling exception: {}", conn.getSessionId());
                    //conn.getIoSession().closeOnFlush(); //must wait until flush to close as we just wrote asynchronously to the stream
                }
            }
        } else if (log.isDebugEnabled()) {
            log.debug("Command type: {}", command.getClass().getName());
        }
	}
	
	/**
	 * 激活连接
	 * @param conn
	 */
	public void notifyConnection(EdgeRTMPMinaConnection conn)
	{
		conn.connected((Server)this.server);
	}

	protected void handleConnect(RTMPConnection conn, Channel channel, Header header, Invoke invoke, RTMP rtmp) {
		//System.out.println("[D5Power] Handleonnect got message from client "+conn.getId());
		final IPendingServiceCall call = invoke.getCall();
		// Get parameters passed from client to NetConnection#connection
		final Map<String, Object> params = invoke.getConnectionParams();

		// Get hostname
		String host = getHostname((String) params.get("tcUrl"));

		// App name as path, but without query string if there is one
		String path = (String) params.get("app");
		if (path.indexOf("?") != -1) {
			int idx = path.indexOf("?");
			params.put("queryString", path.substring(idx));
			path = path.substring(0, idx);
		}
		params.put("path", path);

		//System.out.println("HandleConnect params==========================="+host+'@'+path);
		conn.setup(host, path, params);
		((EdgeRTMPMinaConnection)conn).call = call;

		// IGlobalScope global = server.lookupGlobal(host, path);
		// if(global!=null)
		// {
		// 	final IContext context = global.getContext();
		// 	IScope scope = context.resolveScope(global,path);
		// }
		

		// check the security constraints
		// send back "ConnectionRejected" if fails.
		if (!checkPermission(conn)) {
			call.setStatus(Call.STATUS_ACCESS_DENIED);
			call.setResult(getStatus(NC_CONNECT_REJECTED));
			Invoke reply = new Invoke();
			reply.setCall(call);
			reply.setTransactionId(invoke.getTransactionId());
			channel.write(reply);
			conn.close();
		} else {
			//System.out.println("HandleConnect Checkpass===========================");
			synchronized (rtmp) {
				// connect the origin
				sendConnectMessage(conn); // C0?
				rtmp.setState(RTMP.STATE_EDGE_CONNECT_ORIGIN_SENT);
				Packet packet = new Packet(header);
				packet.setMessage(invoke);
				//System.out.println("[D5Power] Forward connection query from "+conn.getId()+" ,content is "+packet.getMessage());
				forwardPacket(conn, packet); // C1?
				rtmp.setState(RTMP.STATE_ORIGIN_CONNECT_FORWARDED);
				// Evaluate request for AMF3 encoding
				// if (Integer.valueOf(3).equals(params.get("objectEncoding"))) {
				// 	rtmp.setEncoding(Encoding.AMF3);
				// }
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
					rtmp.setEncoding(Encoding.AMF3);
				}
			}
		}
	}

	protected boolean checkPermission(RTMPConnection conn) {
		// TODO check permission per some rules
		return true;
	}

	protected void sendConnectMessage(RTMPConnection conn) {
		//System.out.println("[D5Power] send connection message to origin @ "+conn.getId());
		IMRTMPConnection mrtmpConn = mrtmpManager.lookupMRTMPConnection(conn);
		if (mrtmpConn != null) {
			mrtmpConn.connect(conn.getId());
		}else{
			log.warn("No origin can connect @=====================");
		}
	}

	protected void forwardPacket(RTMPConnection conn, Packet packet) {
		IMRTMPConnection mrtmpConn = mrtmpManager.lookupMRTMPConnection(conn);
		if (mrtmpConn != null) {
			//System.out.println("[D5Power] ForwardPacket( client "+conn.getId()+"),header is "+packet.getHeader());
			mrtmpConn.write(conn.getId(), packet);
		}
	}

	@Override
	public void connectionClosed(RTMPConnection conn) {
		// the state change will be maintained inside connection object.
		conn.close();
	}
	
}
