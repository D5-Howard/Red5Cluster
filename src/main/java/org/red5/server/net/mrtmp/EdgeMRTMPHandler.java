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

import org.apache.mina.core.service.IoHandlerAdapter;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolCodecFactory;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.filter.logging.LoggingFilter;
import org.red5.io.object.StreamAction;
import org.red5.server.api.Red5;
import org.red5.server.api.service.IPendingServiceCall;
import org.red5.server.net.mrtmp.MRTMPPacket.RTMPBody;
import org.red5.server.net.mrtmp.MRTMPPacket.RTMPHeader;
import org.red5.server.net.rtmp.IRTMPConnManager;
import org.red5.server.net.rtmp.RTMPConnection;
import org.red5.server.net.rtmp.codec.RTMP;
import org.red5.server.net.rtmp.event.Invoke;
import org.red5.server.net.rtmp.event.Ping;
import org.red5.server.net.rtmp.message.Constants;
import org.red5.server.net.rtmp.status.StatusObject;
import org.red5.server.service.Call;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Steven Gong (steven.gong@gmail.com)
 */
public class EdgeMRTMPHandler extends IoHandlerAdapter implements Constants {

	private static final Logger log = LoggerFactory.getLogger(EdgeMRTMPHandler.class);

	private IRTMPConnManager rtmpConnManager;
	private IMRTMPEdgeManager mrtmpManager;
	private ProtocolCodecFactory codecFactory;
	
	public void setCodecFactory(ProtocolCodecFactory codecFactory) {
		this.codecFactory = codecFactory;
	}

	public void setMrtmpManager(IMRTMPEdgeManager mrtmpManager) {
		this.mrtmpManager = mrtmpManager;
	}

	public void setRtmpConnManager(IRTMPConnManager rtmpConnManager) {
		this.rtmpConnManager = rtmpConnManager;
	}

	@Override
	public void messageReceived(IoSession session, Object message) throws Exception {
		
		MRTMPPacket mrtmpPacket = (MRTMPPacket) message;
		int clientId = mrtmpPacket.getHeader().getClientId();
		RTMPConnection conn = rtmpConnManager.getConnection(clientId);
		

		//System.out.println("[D5Power] MRTMPEdge get message from origin to client ======================"+conn.getId());
		if (conn == null) {
			log.debug("Client " + clientId + " is already closed.");
			return;
		}
		RTMP rtmpState = conn.getState();
		switch (mrtmpPacket.getHeader().getType()) {
			case MRTMPPacket.CLOSE:
				conn.setStateCode(RTMP.STATE_EDGE_DISCONNECTING);
				conn.close();
				break;
			case MRTMPPacket.RTMP:
				RTMPHeader rtmpHeader = (RTMPHeader) mrtmpPacket.getHeader();
				RTMPBody rtmpBody = (RTMPBody) mrtmpPacket.getBody();
				boolean toDisconnect = false;
				//conn.getWriteLock().lock();
				try {
					if (rtmpState.getState() == RTMP.STATE_ORIGIN_CONNECT_FORWARDED &&
							rtmpHeader.getRtmpType() == TYPE_INVOKE) {
						// we got the connect invocation result from Origin
						// parse the result
						Invoke invoke = (Invoke) rtmpBody.getRtmpPacket().getMessage();
						EdgeRTMPHandler edgeHandler = (EdgeRTMPHandler)conn.getHandler();
						if (edgeHandler!=null && StreamAction.CONNECT.equals(invoke.getCall().getServiceMethodName())) {
							IPendingServiceCall call = ((EdgeRTMPMinaConnection)conn).call;
							if (call!=null && invoke.getCall().getStatus() == Call.STATUS_SUCCESS_RESULT) {
								
								rtmpState.setState(RTMP.STATE_CONNECTED);
								edgeHandler.notifyConnection((EdgeRTMPMinaConnection)conn);
								if (call instanceof IPendingServiceCall) {
									IPendingServiceCall pc = (IPendingServiceCall) call;
									//send fmsver and capabilities
									StatusObject result = ((EdgeRTMPHandler) conn.getHandler()).getStatus("NetConnection.Connect.Success");
									result.setAdditional("fmsVer", Red5.getFMSVersion());
									result.setAdditional("capabilities", Red5.getCapabilities());
									result.setAdditional("mode", Integer.valueOf(1));
									result.setAdditional("data", Red5.getDataVersion());
									pc.setResult(result);
								}
								// Measure initial round-trip time after connecting
								conn.ping(new Ping(Ping.STREAM_BEGIN, 0, -1));
							} else {
								// TODO set EdgeRTMP state to closing ?
								toDisconnect = true;
							}
						}
					}
				} finally {
				//	conn.getWriteLock().unlock();
				}
				log.debug("Forward packet to client: {}", rtmpBody.getRtmpPacket().getMessage());
				// send the packet back to client
				//System.out.println("[D5Power]Back to Flash "+clientId+"===="+rtmpBody.getRtmpPacket()+"========="+rtmpBody.getRtmpPacket().getMessage());
				// if(rtmpBody.getRtmpPacket().getHeader().getDataType()==20)
				// {
				// 	conn.close();
				// 	//return;
				// }
				conn.write(rtmpBody.getRtmpPacket());
				if (toDisconnect) {
					conn.close();
				}
			//	conn.getWriteLock().lock();
				try {
					if (rtmpState.getState() == RTMP.STATE_CONNECTED) {
						//conn.startRoundTripMeasurement();
					}
				} finally {
				//	conn.getWriteLock().unlock();
				}
				break;
			default:
				break;
		}
	}

	@Override
	public void messageSent(IoSession session, Object message) throws Exception {
		// do nothing
	}

	@Override
	public void sessionClosed(IoSession session) throws Exception {
		MRTMPEdgeConnection conn = (MRTMPEdgeConnection) session.getAttribute(MRTMPEdgeConnection.EDGE_CONNECTION_KEY);
		mrtmpManager.unregisterConnection(conn);
		conn.close();
		log.debug("Closed MRTMP Edge Connection " + conn);
	}

	@Override
	public void sessionCreated(IoSession session) throws Exception {
		
		MRTMPEdgeConnection conn = new MRTMPEdgeConnection();
		conn.setIoSession(session);
		System.out.println("[D5Power] EdgeMRTMPHandler MRTMP Client sessionCreated "+conn);
		mrtmpManager.registerConnection(conn);
		session.setAttribute(MRTMPEdgeConnection.EDGE_CONNECTION_KEY, conn);
		session.getFilterChain().addFirst("protocolFilter",
				new ProtocolCodecFilter(this.codecFactory));
		if (log.isDebugEnabled()) {
			session.getFilterChain().addLast("logger", new LoggingFilter());
		}
		//System.out.println("=======================Created MRTMP Edge Connection {}"+conn);
	}

	/** {@inheritDoc} */
    @Override
    public void exceptionCaught(IoSession session, Throwable cause) throws Exception {
        log.debug("Filter chain: {}", session.getFilterChain());
        String sessionId = (String) session.getAttribute(RTMPConnection.RTMP_SESSION_ID);
        if (log.isDebugEnabled()) {
            log.warn("Exception caught on session: {} id: {}", session.getId(), sessionId, cause);
        }
        log.debug("Non-IOException caught on {}", sessionId);
    }
}
