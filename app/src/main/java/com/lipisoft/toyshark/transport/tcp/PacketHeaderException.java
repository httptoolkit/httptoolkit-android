/*
 *  Copyright 2014 AT&T
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package com.lipisoft.toyshark.transport.tcp;
/**
 * 
 * @author Borey Sao
 * Date: May 7, 2014
 */
public class PacketHeaderException extends Exception {
	private static final long serialVersionUID = 1L;
	public PacketHeaderException(String message){
		super(message);
	}
	public PacketHeaderException(String message, Throwable throwable){
		super(message,throwable);
	}
}
