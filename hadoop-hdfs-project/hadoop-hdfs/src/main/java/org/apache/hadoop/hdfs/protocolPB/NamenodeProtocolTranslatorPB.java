/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.protocolPB;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.protocol.AlreadyBeingCreatedException;
import org.apache.hadoop.hdfs.protocol.DatanodeID;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.protocol.proto.HdfsProtos.NamenodeCommandProto;
import org.apache.hadoop.hdfs.protocol.proto.HdfsProtos.VersionRequestProto;
import org.apache.hadoop.hdfs.protocol.proto.NamenodeProtocolProtos.EndCheckpointRequestProto;
import org.apache.hadoop.hdfs.protocol.proto.NamenodeProtocolProtos.ErrorReportRequestProto;
import org.apache.hadoop.hdfs.protocol.proto.NamenodeProtocolProtos.GetBlockKeysRequestProto;
import org.apache.hadoop.hdfs.protocol.proto.NamenodeProtocolProtos.GetBlocksRequestProto;
import org.apache.hadoop.hdfs.protocol.proto.NamenodeProtocolProtos.GetEditLogManifestRequestProto;
import org.apache.hadoop.hdfs.protocol.proto.NamenodeProtocolProtos.GetTransactionIdRequestProto;
import org.apache.hadoop.hdfs.protocol.proto.NamenodeProtocolProtos.RegisterRequestProto;
import org.apache.hadoop.hdfs.protocol.proto.NamenodeProtocolProtos.RollEditLogRequestProto;
import org.apache.hadoop.hdfs.protocol.proto.NamenodeProtocolProtos.StartCheckpointRequestProto;
import org.apache.hadoop.hdfs.protocolR23Compatible.ProtocolSignatureWritable;
import org.apache.hadoop.hdfs.security.token.block.ExportedBlockKeys;
import org.apache.hadoop.hdfs.server.namenode.CheckpointSignature;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.apache.hadoop.hdfs.server.protocol.BlocksWithLocations;
import org.apache.hadoop.hdfs.server.protocol.NamenodeCommand;
import org.apache.hadoop.hdfs.server.protocol.NamenodeProtocol;
import org.apache.hadoop.hdfs.server.protocol.NamenodeRegistration;
import org.apache.hadoop.hdfs.server.protocol.NamespaceInfo;
import org.apache.hadoop.hdfs.server.protocol.RemoteEditLogManifest;
import org.apache.hadoop.io.retry.RetryPolicies;
import org.apache.hadoop.io.retry.RetryPolicy;
import org.apache.hadoop.io.retry.RetryProxy;
import org.apache.hadoop.ipc.ProtobufHelper;
import org.apache.hadoop.ipc.ProtocolSignature;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.UserGroupInformation;

import com.google.protobuf.RpcController;
import com.google.protobuf.ServiceException;

/**
 * This class is the client side translator to translate the requests made on
 * {@link NamenodeProtocol} interfaces to the RPC server implementing
 * {@link NamenodeProtocolPB}.
 */
@InterfaceAudience.Private
@InterfaceStability.Stable
public class NamenodeProtocolTranslatorPB implements NamenodeProtocol,
    Closeable {
  /** RpcController is not used and hence is set to null */
  private final static RpcController NULL_CONTROLLER = null;
  
  /*
   * Protobuf requests with no parameters instantiated only once
   */
  private static final GetBlockKeysRequestProto GET_BLOCKKEYS = 
      GetBlockKeysRequestProto.newBuilder().build();
  private static final GetTransactionIdRequestProto GET_TRANSACTIONID = 
      GetTransactionIdRequestProto.newBuilder().build();
  private static final RollEditLogRequestProto ROLL_EDITLOG = 
      RollEditLogRequestProto.newBuilder().build();
  private static final VersionRequestProto VERSION_REQUEST = 
      VersionRequestProto.newBuilder().build();

  final private NamenodeProtocolPB rpcProxy;



  private static NamenodeProtocolPB createNamenode(
      InetSocketAddress nameNodeAddr, Configuration conf,
      UserGroupInformation ugi) throws IOException {
    return RPC.getProxy(NamenodeProtocolPB.class,
        RPC.getProtocolVersion(NamenodeProtocolPB.class), nameNodeAddr, ugi,
        conf, NetUtils.getSocketFactory(conf, NamenodeProtocolPB.class));
  }

  /** Create a {@link NameNode} proxy */
  static NamenodeProtocolPB createNamenodeWithRetry(
      NamenodeProtocolPB rpcNamenode) {
    RetryPolicy createPolicy = RetryPolicies
        .retryUpToMaximumCountWithFixedSleep(5,
            HdfsConstants.LEASE_SOFTLIMIT_PERIOD, TimeUnit.MILLISECONDS);
    Map<Class<? extends Exception>, RetryPolicy> remoteExceptionToPolicyMap = new HashMap<Class<? extends Exception>, RetryPolicy>();
    remoteExceptionToPolicyMap.put(AlreadyBeingCreatedException.class,
        createPolicy);

    Map<Class<? extends Exception>, RetryPolicy> exceptionToPolicyMap = new HashMap<Class<? extends Exception>, RetryPolicy>();
    exceptionToPolicyMap.put(RemoteException.class, RetryPolicies
        .retryByRemoteException(RetryPolicies.TRY_ONCE_THEN_FAIL,
            remoteExceptionToPolicyMap));
    RetryPolicy methodPolicy = RetryPolicies.retryByException(
        RetryPolicies.TRY_ONCE_THEN_FAIL, exceptionToPolicyMap);
    Map<String, RetryPolicy> methodNameToPolicyMap = new HashMap<String, RetryPolicy>();

    methodNameToPolicyMap.put("create", methodPolicy);

    return (NamenodeProtocolPB) RetryProxy.create(NamenodeProtocolPB.class,
        rpcNamenode, methodNameToPolicyMap);
  }

  public NamenodeProtocolTranslatorPB(InetSocketAddress nameNodeAddr,
      Configuration conf, UserGroupInformation ugi) throws IOException {
    rpcProxy = createNamenodeWithRetry(createNamenode(nameNodeAddr, conf, ugi));
  }

  public void close() {
    RPC.stopProxy(rpcProxy);
  }

  @Override
  public ProtocolSignature getProtocolSignature(String protocolName,
      long clientVersion, int clientMethodHash) throws IOException {
    return ProtocolSignatureWritable.convert(rpcProxy.getProtocolSignature2(
        protocolName, clientVersion, clientMethodHash));
  }

  @Override
  public long getProtocolVersion(String protocolName, long clientVersion)
      throws IOException {
    return rpcProxy.getProtocolVersion(protocolName, clientVersion);
  }

  @Override
  public BlocksWithLocations getBlocks(DatanodeInfo datanode, long size)
      throws IOException {
    GetBlocksRequestProto req = GetBlocksRequestProto.newBuilder()
        .setDatanode(PBHelper.convert((DatanodeID)datanode)).setSize(size)
        .build();
    try {
      return PBHelper.convert(rpcProxy.getBlocks(NULL_CONTROLLER, req)
          .getBlocks());
    } catch (ServiceException e) {
      throw ProtobufHelper.getRemoteException(e);
    }
  }

  @Override
  public ExportedBlockKeys getBlockKeys() throws IOException {
    try {
      return PBHelper.convert(rpcProxy.getBlockKeys(NULL_CONTROLLER,
          GET_BLOCKKEYS).getKeys());
    } catch (ServiceException e) {
      throw ProtobufHelper.getRemoteException(e);
    }
  }

  @Override
  public long getTransactionID() throws IOException {
    try {
      return rpcProxy.getTransationId(NULL_CONTROLLER, GET_TRANSACTIONID)
          .getTxId();
    } catch (ServiceException e) {
      throw ProtobufHelper.getRemoteException(e);
    }
  }

  @Override
  @SuppressWarnings("deprecation")
  public CheckpointSignature rollEditLog() throws IOException {
    try {
      return PBHelper.convert(rpcProxy.rollEditLog(NULL_CONTROLLER,
          ROLL_EDITLOG).getSignature());
    } catch (ServiceException e) {
      throw ProtobufHelper.getRemoteException(e);
    }
  }

  @Override
  public NamespaceInfo versionRequest() throws IOException {
    try {
      return PBHelper.convert(rpcProxy.versionRequest(NULL_CONTROLLER,
          VERSION_REQUEST).getInfo());
    } catch (ServiceException e) {
      throw ProtobufHelper.getRemoteException(e);
    }
  }

  @Override
  public void errorReport(NamenodeRegistration registration, int errorCode,
      String msg) throws IOException {
    ErrorReportRequestProto req = ErrorReportRequestProto.newBuilder()
        .setErrorCode(errorCode).setMsg(msg)
        .setRegistration(PBHelper.convert(registration)).build();
    try {
      rpcProxy.errorReport(NULL_CONTROLLER, req);
    } catch (ServiceException e) {
      throw ProtobufHelper.getRemoteException(e);
    }
  }

  @Override
  public NamenodeRegistration register(NamenodeRegistration registration)
      throws IOException {
    RegisterRequestProto req = RegisterRequestProto.newBuilder()
        .setRegistration(PBHelper.convert(registration)).build();
    try {
      return PBHelper.convert(rpcProxy.register(NULL_CONTROLLER, req)
          .getRegistration());
    } catch (ServiceException e) {
      throw ProtobufHelper.getRemoteException(e);
    }
  }

  @Override
  public NamenodeCommand startCheckpoint(NamenodeRegistration registration)
      throws IOException {
    StartCheckpointRequestProto req = StartCheckpointRequestProto.newBuilder()
        .setRegistration(PBHelper.convert(registration)).build();
    NamenodeCommandProto cmd;
    try {
      cmd = rpcProxy.startCheckpoint(NULL_CONTROLLER, req).getCommand();
    } catch (ServiceException e) {
      throw ProtobufHelper.getRemoteException(e);
    }
    return PBHelper.convert(cmd);
  }

  @Override
  public void endCheckpoint(NamenodeRegistration registration,
      CheckpointSignature sig) throws IOException {
    EndCheckpointRequestProto req = EndCheckpointRequestProto.newBuilder()
        .setRegistration(PBHelper.convert(registration))
        .setSignature(PBHelper.convert(sig)).build();
    try {
      rpcProxy.endCheckpoint(NULL_CONTROLLER, req);
    } catch (ServiceException e) {
      throw ProtobufHelper.getRemoteException(e);
    }
  }

  @Override
  public RemoteEditLogManifest getEditLogManifest(long sinceTxId)
      throws IOException {
    GetEditLogManifestRequestProto req = GetEditLogManifestRequestProto
        .newBuilder().setSinceTxId(sinceTxId).build();
    try {
      return PBHelper.convert(rpcProxy.getEditLogManifest(NULL_CONTROLLER, req)
          .getManifest());
    } catch (ServiceException e) {
      throw ProtobufHelper.getRemoteException(e);
    }
  }
}
