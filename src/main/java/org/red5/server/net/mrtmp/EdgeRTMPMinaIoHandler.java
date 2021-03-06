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

import org.apache.mina.core.session.IoSession;
import org.red5.server.net.rtmp.RTMPConnManager;
import org.red5.server.net.rtmp.RTMPConnection;
import org.red5.server.net.rtmp.RTMPMinaConnection;
import org.red5.server.net.rtmp.RTMPMinaIoHandler;

public class EdgeRTMPMinaIoHandler extends RTMPMinaIoHandler {
	
	@Override
	protected RTMPMinaConnection createRTMPMinaConnection() {
		//System.out.println("Create RTMP Mina Connection=========================");
		RTMPMinaConnection conn = (RTMPMinaConnection) RTMPConnManager.getInstance().createConnection(EdgeRTMPMinaConnection.class);
		//System.out.println("[D5Power] Create RTMP Mina Connection,clint "+conn.getId()+"==========");
		return conn;
	}

	@Override
    public void messageReceived(IoSession session, Object message) throws Exception {
			String sessionId = (String) session.getAttribute(RTMPConnection.RTMP_SESSION_ID);
			RTMPMinaConnection conn = (RTMPMinaConnection) RTMPConnManager.getInstance().getConnectionBySessionId(sessionId);
			//System.out.println("[D5Power] Client message received,clint "+conn.getId()+"==========");
			super.messageReceived(session, message);
	}
}
