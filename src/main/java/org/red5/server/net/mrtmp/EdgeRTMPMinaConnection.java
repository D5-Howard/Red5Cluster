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

import org.red5.server.Server;
import org.red5.server.api.service.IPendingServiceCall;
import org.red5.server.net.rtmp.RTMPMinaConnection;
import org.red5.server.net.rtmp.codec.RTMP;
import org.red5.server.net.rtmp.message.Packet;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

public class EdgeRTMPMinaConnection extends RTMPMinaConnection {
	
	private static int index = 1;
	private IMRTMPEdgeManager mrtmpManager;

	private int _index = -1;

	public IPendingServiceCall call = null;

	public EdgeRTMPMinaConnection(){
		super();
		this._index = EdgeRTMPMinaConnection.index;
		EdgeRTMPMinaConnection.index++;
		//System.out.println("[D5Power] instance EdgeRTMPMinaConnection "+this._index);
	}

	@Override
	public int getId()
	{
		//int id = client != null ? client.getId().hashCode() : -1;
		//System.out.println("[D5Power] Client id "+id);
		return _index;
	}

	private boolean _connected;
	/**
	 * 完成连接
	 * @param server
	 */
	public void connected(Server server)
	{
		// 激活连接（在原版中只有在scope被挂接后，才会运行本方法）
		server.notifyConnected(this);
		// 设置连接状态（在原版中，只有scope被挂接，不为null的情况下，才会返回true @see isConnected）
		this._connected = true;
		// 完成握手
		stopWaitForHandshake();
		// 开始心跳
		startRoundTripMeasurement();
	}

	@Override
	/**
     * Check whether connection is alive
     * 
     * @return true if connection is bound to scope, false otherwise
     */
    public boolean isConnected() {
        //log.debug("Connected: {}", (scope != null));
        return this._connected;
    }


	//@Override
	/**
     * Handle the incoming message.
     * 
     * @param message
     *            message
     */
    // public void handleMessageReceived(Packet message) {
	// 	if(executor==null)
	// 	{
	// 		log.debug("Executor is null on {} state: {}", sessionId, RTMP.states[getStateCode()]);
    //         // pass message to the handler
    //         try {
    //             handler.messageReceived(this, message);
    //         } catch (Exception e) {
    //             log.error("Error processing received message {} state: {}", sessionId, RTMP.states[getStateCode()], e);
    //         }
	// 	}else{
	// 		super.handleMessageReceived(message);
	// 	}
        
    // }


	public void setMrtmpManager(IMRTMPEdgeManager mrtmpManager) {
		System.out.println("@@@====================set manager"+mrtmpManager);
		this.mrtmpManager = mrtmpManager;
	}

	@Override
	public void close() {
		boolean needNotifyOrigin = false;
		RTMP state = getState();
		//getWriteLock().lock();
		try{
			if (state.getState() == RTMP.STATE_CONNECTED) {
				needNotifyOrigin = true;
				// now we are disconnecting ourselves
				state.setState(RTMP.STATE_EDGE_DISCONNECTING);
			}
		} finally {
			//getWriteLock().unlock();
		}
		if (needNotifyOrigin) {
			IMRTMPConnection conn = mrtmpManager.lookupMRTMPConnection(this);
			if (conn != null) {
				conn.disconnect(getId());
			}
		}
	//	getWriteLock().lock();
		try {
			if (state.getState() == RTMP.STATE_DISCONNECTED) {
				return;
			} else {
				state.setState(RTMP.STATE_DISCONNECTED);
			}
		} finally {
		//	getWriteLock().unlock();
		}
		super.close();
	}

//	@Override
//	protected void startWaitForHandshake(ISchedulingService service) {
//		// FIXME: do nothing to avoid disconnect.
//	}
}
