package com.linkedin.pinot.transport.scattergather;

import io.netty.buffer.ByteBuf;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.HashedWheelTimer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.PatternLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.linkedin.pinot.common.query.QueryExecutor;
import com.linkedin.pinot.common.response.ServerInstance;
import com.linkedin.pinot.transport.common.BucketingSelection;
import com.linkedin.pinot.transport.common.CompositeFuture;
import com.linkedin.pinot.transport.common.ReplicaSelection;
import com.linkedin.pinot.transport.common.ReplicaSelectionGranularity;
import com.linkedin.pinot.transport.common.RoundRobinReplicaSelection;
import com.linkedin.pinot.transport.common.SegmentId;
import com.linkedin.pinot.transport.common.SegmentIdSet;
import com.linkedin.pinot.transport.metrics.NettyClientMetrics;
import com.linkedin.pinot.transport.netty.NettyClientConnection;
import com.linkedin.pinot.transport.netty.NettyServer.RequestHandler;
import com.linkedin.pinot.transport.netty.NettyServer.RequestHandlerFactory;
import com.linkedin.pinot.transport.netty.NettyTCPServer;
import com.linkedin.pinot.transport.netty.PooledNettyClientResourceManager;
import com.linkedin.pinot.transport.pool.KeyedPoolImpl;
import com.linkedin.pinot.transport.scattergather.ScatterGatherImpl.ScatterGatherRequestContext;
import com.yammer.metrics.core.MetricsRegistry;


public class TestScatterGather {

  protected static Logger LOG = LoggerFactory.getLogger(TestScatterGather.class);

  static {
    org.apache.log4j.Logger.getRootLogger().addAppender(
        new ConsoleAppender(new PatternLayout(PatternLayout.TTCC_CONVERSION_PATTERN), "System.out"));
    org.apache.log4j.Logger.getRootLogger().setLevel(Level.ERROR);
  }

  @Test
  public void testSelectServers() throws Exception {
    ScatterGatherImpl scImpl = new ScatterGatherImpl(null);

    {
      // 1 server with 2 partitions
      SegmentIdSet pg = new SegmentIdSet();
      pg.addSegment(new SegmentId("0"));
      pg.addSegment(new SegmentId("1"));

      ServerInstance serverInstance1 = new ServerInstance("localhost", 1011);
      List<ServerInstance> instances = new ArrayList<ServerInstance>();
      instances.add(serverInstance1);
      Map<SegmentIdSet, List<ServerInstance>> pgMap = new HashMap<SegmentIdSet, List<ServerInstance>>();
      Map<List<ServerInstance>, SegmentIdSet> invMap = new HashMap<List<ServerInstance>, SegmentIdSet>();

      pgMap.put(pg, instances);
      invMap.put(instances, pg);
      String request = "request_0";
      Map<SegmentIdSet, String> pgMapStr = new HashMap<SegmentIdSet, String>();
      pgMapStr.put(pg, request);
      ScatterGatherRequest req = new TestScatterGatherRequest(pgMap, pgMapStr);
      ScatterGatherRequestContext ctxt = new ScatterGatherRequestContext(req);
      ctxt.setInvertedMap(invMap);
      scImpl.selectServices(ctxt);
      Map<ServerInstance, SegmentIdSet> resultMap = ctxt.getSelectedServers();
      AssertJUnit.assertEquals("Count", 1, resultMap.size());
      AssertJUnit.assertEquals("Element check", pg, resultMap.get(serverInstance1));
      System.out.println(ctxt);
    }

    {
      // 2 server with 2 partitions each
      SegmentIdSet pg = new SegmentIdSet();
      pg.addSegment(new SegmentId("0"));
      pg.addSegment(new SegmentId("1"));
      SegmentIdSet pg2 = new SegmentIdSet();
      pg2.addSegment(new SegmentId("2"));
      pg2.addSegment(new SegmentId("3"));
      ServerInstance serverInstance1 = new ServerInstance("localhost", 1011);
      ServerInstance serverInstance2 = new ServerInstance("localhost", 1012);
      List<ServerInstance> instances = new ArrayList<ServerInstance>();
      instances.add(serverInstance1);
      List<ServerInstance> instances2 = new ArrayList<ServerInstance>();
      instances2.add(serverInstance2);

      Map<SegmentIdSet, List<ServerInstance>> pgMap = new HashMap<SegmentIdSet, List<ServerInstance>>();
      Map<List<ServerInstance>, SegmentIdSet> invMap = new HashMap<List<ServerInstance>, SegmentIdSet>();

      pgMap.put(pg, instances);
      pgMap.put(pg2, instances2);
      invMap.put(instances, pg);
      invMap.put(instances2, pg2);
      String request = "request_0";
      Map<SegmentIdSet, String> pgMapStr = new HashMap<SegmentIdSet, String>();
      pgMapStr.put(pg, request);
      ScatterGatherRequest req = new TestScatterGatherRequest(pgMap, pgMapStr);
      ScatterGatherRequestContext ctxt = new ScatterGatherRequestContext(req);
      ctxt.setInvertedMap(invMap);
      scImpl.selectServices(ctxt);
      Map<ServerInstance, SegmentIdSet> resultMap = ctxt.getSelectedServers();
      AssertJUnit.assertEquals("Count", 2, resultMap.size());
      AssertJUnit.assertEquals("Element check", pg, resultMap.get(serverInstance1));
      AssertJUnit.assertEquals("Element check", pg2, resultMap.get(serverInstance2));
      System.out.println(ctxt);
    }

    {
      // 2 servers sharing 2 partitions (Round-Robin selection) Partition-Group Granularity
      SegmentIdSet pg = new SegmentIdSet();
      pg.addSegment(new SegmentId("0"));
      pg.addSegment(new SegmentId("1"));
      ServerInstance serverInstance1 = new ServerInstance("localhost", 1011);
      ServerInstance serverInstance2 = new ServerInstance("localhost", 1012);
      List<ServerInstance> instances = new ArrayList<ServerInstance>();
      instances.add(serverInstance1);
      instances.add(serverInstance2);

      Map<SegmentIdSet, List<ServerInstance>> pgMap = new HashMap<SegmentIdSet, List<ServerInstance>>();
      Map<List<ServerInstance>, SegmentIdSet> invMap = new HashMap<List<ServerInstance>, SegmentIdSet>();

      pgMap.put(pg, instances);
      invMap.put(instances, pg);
      String request = "request_0";
      Map<SegmentIdSet, String> pgMapStr = new HashMap<SegmentIdSet, String>();
      pgMapStr.put(pg, request);
      ScatterGatherRequest req =
          new TestScatterGatherRequest(pgMap, pgMapStr, new RoundRobinReplicaSelection(),
              ReplicaSelectionGranularity.SEGMENT_ID_SET, 0, 10000);
      ScatterGatherRequestContext ctxt = new ScatterGatherRequestContext(req);
      ctxt.setInvertedMap(invMap);
      scImpl.selectServices(ctxt);
      Map<ServerInstance, SegmentIdSet> resultMap = ctxt.getSelectedServers();
      AssertJUnit.assertEquals("Count", 1, resultMap.size());
      AssertJUnit.assertEquals("Element check", pg, resultMap.get(serverInstance1)); // first server is getting selected
      System.out.println(ctxt);

      // Run selection again. Now the second server should be selected
      scImpl.selectServices(ctxt);
      resultMap = ctxt.getSelectedServers();
      AssertJUnit.assertEquals("Count", 1, resultMap.size());
      AssertJUnit.assertEquals("Element check", pg, resultMap.get(serverInstance2)); // second server is getting selected
      System.out.println(ctxt);
    }

    {
      // 2 servers sharing 2 partitions (Round-Robin selection) Partition Granularity
      SegmentIdSet pg = new SegmentIdSet();
      pg.addSegment(new SegmentId("0"));
      pg.addSegment(new SegmentId("1"));
      ServerInstance serverInstance1 = new ServerInstance("localhost", 1011);
      ServerInstance serverInstance2 = new ServerInstance("localhost", 1012);
      List<ServerInstance> instances = new ArrayList<ServerInstance>();
      instances.add(serverInstance1);
      instances.add(serverInstance2);

      Map<SegmentIdSet, List<ServerInstance>> pgMap = new HashMap<SegmentIdSet, List<ServerInstance>>();
      Map<List<ServerInstance>, SegmentIdSet> invMap = new HashMap<List<ServerInstance>, SegmentIdSet>();

      pgMap.put(pg, instances);
      invMap.put(instances, pg);
      String request = "request_0";
      Map<SegmentIdSet, String> pgMapStr = new HashMap<SegmentIdSet, String>();
      pgMapStr.put(pg, request);
      ScatterGatherRequest req =
          new TestScatterGatherRequest(pgMap, pgMapStr, new RoundRobinReplicaSelection(),
              ReplicaSelectionGranularity.SEGMENT_ID, 0, 10000);
      ScatterGatherRequestContext ctxt = new ScatterGatherRequestContext(req);
      ctxt.setInvertedMap(invMap);
      scImpl.selectServices(ctxt);
      Map<ServerInstance, SegmentIdSet> resultMap = ctxt.getSelectedServers();
      AssertJUnit.assertEquals("Count", 2, resultMap.size());
      AssertJUnit.assertFalse("Element check", resultMap.get(serverInstance1).equals(resultMap.get(serverInstance2))); // first server is getting selected
      System.out.println(ctxt);

      // Run selection again. Now the second server should be selected
      scImpl.selectServices(ctxt);
      resultMap = ctxt.getSelectedServers();
      AssertJUnit.assertEquals("Count", 2, resultMap.size());
      AssertJUnit.assertFalse("Element check", resultMap.get(serverInstance1).equals(resultMap.get(serverInstance2))); // first server is getting selected
      System.out.println(ctxt);
    }
  }

  @Test
  public void testSingleServer() throws Exception {

    MetricsRegistry registry = new MetricsRegistry();

    // Server start
    int serverPort = 7071;
    NettyTCPServer server1 = new NettyTCPServer(serverPort, new TestRequestHandlerFactory(0, 1), null);
    Thread t1 = new Thread(server1);
    t1.start();

    //Client setup
    ScheduledExecutorService timedExecutor = new ScheduledThreadPoolExecutor(1);
    ExecutorService service = new ThreadPoolExecutor(1, 1, 1, TimeUnit.DAYS, new LinkedBlockingDeque<Runnable>());
    EventLoopGroup eventLoopGroup = new NioEventLoopGroup();
    NettyClientMetrics clientMetrics = new NettyClientMetrics(registry, "client_");
    PooledNettyClientResourceManager rm =
        new PooledNettyClientResourceManager(eventLoopGroup, new HashedWheelTimer(), clientMetrics);
    KeyedPoolImpl<ServerInstance, NettyClientConnection> pool =
        new KeyedPoolImpl<ServerInstance, NettyClientConnection>(1, 1, 300000, 1, rm, timedExecutor, service, registry);
    rm.setPool(pool);

    ScatterGatherImpl scImpl = new ScatterGatherImpl(pool);

    SegmentIdSet pg = new SegmentIdSet();
    pg.addSegment(new SegmentId("0"));
    ServerInstance serverInstance1 = new ServerInstance("localhost", serverPort);
    List<ServerInstance> instances = new ArrayList<ServerInstance>();
    instances.add(serverInstance1);
    Map<SegmentIdSet, List<ServerInstance>> pgMap = new HashMap<SegmentIdSet, List<ServerInstance>>();
    pgMap.put(pg, instances);
    String request = "request_0";
    Map<SegmentIdSet, String> pgMapStr = new HashMap<SegmentIdSet, String>();
    pgMapStr.put(pg, request);
    ScatterGatherRequest req = new TestScatterGatherRequest(pgMap, pgMapStr);

    CompositeFuture<ServerInstance, ByteBuf> fut = scImpl.scatterGather(req);
    Map<ServerInstance, ByteBuf> v = fut.get();
    ByteBuf b = v.get(serverInstance1);
    byte[] b2 = new byte[b.readableBytes()];
    b.readBytes(b2);
    String response = new String(b2);
    AssertJUnit.assertEquals("response_0_0", response);
    AssertJUnit.assertEquals(1, v.size());
    server1.shutdownGracefully();
    pool.shutdown();
    service.shutdown();
    eventLoopGroup.shutdownGracefully();
  }

  @Test
  public void testMultipleServerHappy() throws Exception {

    MetricsRegistry registry = new MetricsRegistry();

    // Server start
    int serverPort1 = 7071;
    int serverPort2 = 7072;
    int serverPort3 = 7073;
    int serverPort4 = 7074;
    NettyTCPServer server1 = new NettyTCPServer(serverPort1, new TestRequestHandlerFactory(0, 1), null);
    NettyTCPServer server2 = new NettyTCPServer(serverPort2, new TestRequestHandlerFactory(1, 1), null);
    NettyTCPServer server3 = new NettyTCPServer(serverPort3, new TestRequestHandlerFactory(2, 1), null);
    NettyTCPServer server4 = new NettyTCPServer(serverPort4, new TestRequestHandlerFactory(3, 1), null);

    Thread t1 = new Thread(server1);
    Thread t2 = new Thread(server2);
    Thread t3 = new Thread(server3);
    Thread t4 = new Thread(server4);
    t1.start();
    t2.start();
    t3.start();
    t4.start();

    //Client setup
    ScheduledExecutorService timedExecutor = new ScheduledThreadPoolExecutor(1);
    ExecutorService service = new ThreadPoolExecutor(1, 1, 1, TimeUnit.DAYS, new LinkedBlockingDeque<Runnable>());
    EventLoopGroup eventLoopGroup = new NioEventLoopGroup();
    NettyClientMetrics clientMetrics = new NettyClientMetrics(registry, "client_");
    PooledNettyClientResourceManager rm =
        new PooledNettyClientResourceManager(eventLoopGroup, new HashedWheelTimer(), clientMetrics);
    KeyedPoolImpl<ServerInstance, NettyClientConnection> pool =
        new KeyedPoolImpl<ServerInstance, NettyClientConnection>(1, 1, 300000, 1, rm, timedExecutor, service, registry);
    rm.setPool(pool);

    SegmentIdSet pg1 = new SegmentIdSet();
    pg1.addSegment(new SegmentId("0"));
    SegmentIdSet pg2 = new SegmentIdSet();
    pg2.addSegment(new SegmentId("1"));
    SegmentIdSet pg3 = new SegmentIdSet();
    pg3.addSegment(new SegmentId("2"));
    SegmentIdSet pg4 = new SegmentIdSet();
    pg4.addSegment(new SegmentId("3"));

    ServerInstance serverInstance1 = new ServerInstance("localhost", serverPort1);
    ServerInstance serverInstance2 = new ServerInstance("localhost", serverPort2);
    ServerInstance serverInstance3 = new ServerInstance("localhost", serverPort3);
    ServerInstance serverInstance4 = new ServerInstance("localhost", serverPort4);

    List<ServerInstance> instances1 = new ArrayList<ServerInstance>();
    instances1.add(serverInstance1);
    List<ServerInstance> instances2 = new ArrayList<ServerInstance>();
    instances2.add(serverInstance2);
    List<ServerInstance> instances3 = new ArrayList<ServerInstance>();
    instances3.add(serverInstance3);
    List<ServerInstance> instances4 = new ArrayList<ServerInstance>();
    instances4.add(serverInstance4);
    Map<SegmentIdSet, List<ServerInstance>> pgMap = new HashMap<SegmentIdSet, List<ServerInstance>>();
    pgMap.put(pg1, instances1);
    pgMap.put(pg2, instances2);
    pgMap.put(pg3, instances3);
    pgMap.put(pg4, instances4);

    String request1 = "request_0";
    String request2 = "request_1";
    String request3 = "request_2";
    String request4 = "request_3";

    Map<SegmentIdSet, String> pgMapStr = new HashMap<SegmentIdSet, String>();
    pgMapStr.put(pg1, request1);
    pgMapStr.put(pg2, request2);
    pgMapStr.put(pg3, request3);
    pgMapStr.put(pg4, request4);

    ScatterGatherRequest req = new TestScatterGatherRequest(pgMap, pgMapStr);
    ScatterGatherImpl scImpl = new ScatterGatherImpl(pool);
    CompositeFuture<ServerInstance, ByteBuf> fut = scImpl.scatterGather(req);
    Map<ServerInstance, ByteBuf> v = fut.get();
    AssertJUnit.assertEquals(4, v.size());

    ByteBuf b = v.get(serverInstance1);
    byte[] b2 = new byte[b.readableBytes()];
    b.readBytes(b2);
    String response = new String(b2);
    AssertJUnit.assertEquals("response_0_0", response);
    b = v.get(serverInstance2);
    b2 = new byte[b.readableBytes()];
    b.readBytes(b2);
    response = new String(b2);
    AssertJUnit.assertEquals("response_1_0", response);
    b = v.get(serverInstance3);
    b2 = new byte[b.readableBytes()];
    b.readBytes(b2);
    response = new String(b2);
    AssertJUnit.assertEquals("response_2_0", response);
    b = v.get(serverInstance4);
    b2 = new byte[b.readableBytes()];
    b.readBytes(b2);
    response = new String(b2);
    AssertJUnit.assertEquals("response_3_0", response);

    server1.shutdownGracefully();
    server2.shutdownGracefully();
    server3.shutdownGracefully();
    server4.shutdownGracefully();
    pool.shutdown();
    service.shutdown();
    eventLoopGroup.shutdownGracefully();
  }

  @Test
  public void testMultipleServerTimeout() throws Exception {

    MetricsRegistry registry = new MetricsRegistry();

    // Server start
    int serverPort1 = 7081;
    int serverPort2 = 7082;
    int serverPort3 = 7083;
    int serverPort4 = 7084; // Timeout server
    NettyTCPServer server1 = new NettyTCPServer(serverPort1, new TestRequestHandlerFactory(0, 1), null);
    NettyTCPServer server2 = new NettyTCPServer(serverPort2, new TestRequestHandlerFactory(1, 1), null);
    NettyTCPServer server3 = new NettyTCPServer(serverPort3, new TestRequestHandlerFactory(2, 1), null);
    NettyTCPServer server4 = new NettyTCPServer(serverPort4, new TestRequestHandlerFactory(3, 1, 7000, false), null);

    Thread t1 = new Thread(server1);
    Thread t2 = new Thread(server2);
    Thread t3 = new Thread(server3);
    Thread t4 = new Thread(server4);
    t1.start();
    t2.start();
    t3.start();
    t4.start();

    //Client setup
    ScheduledExecutorService timedExecutor = new ScheduledThreadPoolExecutor(1);
    ExecutorService service = new ThreadPoolExecutor(1, 1, 1, TimeUnit.DAYS, new LinkedBlockingDeque<Runnable>());
    EventLoopGroup eventLoopGroup = new NioEventLoopGroup();
    NettyClientMetrics clientMetrics = new NettyClientMetrics(registry, "client_");
    PooledNettyClientResourceManager rm =
        new PooledNettyClientResourceManager(eventLoopGroup, new HashedWheelTimer(), clientMetrics);
    KeyedPoolImpl<ServerInstance, NettyClientConnection> pool =
        new KeyedPoolImpl<ServerInstance, NettyClientConnection>(1, 1, 300000, 1, rm, timedExecutor, service, registry);
    rm.setPool(pool);

    SegmentIdSet pg1 = new SegmentIdSet();
    pg1.addSegment(new SegmentId("0"));
    SegmentIdSet pg2 = new SegmentIdSet();
    pg2.addSegment(new SegmentId("1"));
    SegmentIdSet pg3 = new SegmentIdSet();
    pg3.addSegment(new SegmentId("2"));
    SegmentIdSet pg4 = new SegmentIdSet();
    pg4.addSegment(new SegmentId("3"));

    ServerInstance serverInstance1 = new ServerInstance("localhost", serverPort1);
    ServerInstance serverInstance2 = new ServerInstance("localhost", serverPort2);
    ServerInstance serverInstance3 = new ServerInstance("localhost", serverPort3);
    ServerInstance serverInstance4 = new ServerInstance("localhost", serverPort4);

    List<ServerInstance> instances1 = new ArrayList<ServerInstance>();
    instances1.add(serverInstance1);
    List<ServerInstance> instances2 = new ArrayList<ServerInstance>();
    instances2.add(serverInstance2);
    List<ServerInstance> instances3 = new ArrayList<ServerInstance>();
    instances3.add(serverInstance3);
    List<ServerInstance> instances4 = new ArrayList<ServerInstance>();
    instances4.add(serverInstance4);
    Map<SegmentIdSet, List<ServerInstance>> pgMap = new HashMap<SegmentIdSet, List<ServerInstance>>();
    pgMap.put(pg1, instances1);
    pgMap.put(pg2, instances2);
    pgMap.put(pg3, instances3);
    pgMap.put(pg4, instances4);

    String request1 = "request_0";
    String request2 = "request_1";
    String request3 = "request_2";
    String request4 = "request_3";

    Map<SegmentIdSet, String> pgMapStr = new HashMap<SegmentIdSet, String>();
    pgMapStr.put(pg1, request1);
    pgMapStr.put(pg2, request2);
    pgMapStr.put(pg3, request3);
    pgMapStr.put(pg4, request4);

    ScatterGatherRequest req =
        new TestScatterGatherRequest(pgMap, pgMapStr, new RoundRobinReplicaSelection(),
            ReplicaSelectionGranularity.SEGMENT_ID_SET, 0, 1000);
    ScatterGatherImpl scImpl = new ScatterGatherImpl(pool);
    CompositeFuture<ServerInstance, ByteBuf> fut = scImpl.scatterGather(req);
    Map<ServerInstance, ByteBuf> v = fut.get();

    //Only 3 servers return value.
    AssertJUnit.assertEquals(3, v.size());
    ByteBuf b = v.get(serverInstance1);
    byte[] b2 = new byte[b.readableBytes()];
    b.readBytes(b2);
    String response = new String(b2);
    AssertJUnit.assertEquals("response_0_0", response);
    b = v.get(serverInstance2);
    b2 = new byte[b.readableBytes()];
    b.readBytes(b2);
    response = new String(b2);
    AssertJUnit.assertEquals("response_1_0", response);
    b = v.get(serverInstance3);
    b2 = new byte[b.readableBytes()];
    b.readBytes(b2);
    response = new String(b2);
    AssertJUnit.assertEquals("response_2_0", response);

    //No  response from 4th server
    AssertJUnit.assertNull("No response from 4th server", v.get(serverInstance4));

    Map<ServerInstance, Throwable> errorMap = fut.getError();
    AssertJUnit.assertEquals("One error", 1, errorMap.size());
    AssertJUnit.assertNotNull("Server4 returned timeout", errorMap.get(serverInstance4));
    System.out.println("Error is :" + errorMap.get(serverInstance4));

    server1.shutdownGracefully();
    server2.shutdownGracefully();
    server3.shutdownGracefully();
    server4.shutdownGracefully();

    System.out.println("Pool Stats :" + pool.getStats());

    pool.shutdown();
    service.shutdown();
    eventLoopGroup.shutdownGracefully();
    pool.getStats().refresh();
    AssertJUnit.assertEquals("Total Bad destroyed", 1, pool.getStats().getTotalBadDestroyed());
  }

  @Test
  public void testMultipleServerError() throws Exception {

    MetricsRegistry registry = new MetricsRegistry();

    // Server start
    int serverPort1 = 7091;
    int serverPort2 = 7092;
    int serverPort3 = 7093;
    int serverPort4 = 7094; // error server
    NettyTCPServer server1 = new NettyTCPServer(serverPort1, new TestRequestHandlerFactory(0, 1), null);
    NettyTCPServer server2 = new NettyTCPServer(serverPort2, new TestRequestHandlerFactory(1, 1), null);
    NettyTCPServer server3 = new NettyTCPServer(serverPort3, new TestRequestHandlerFactory(2, 1), null);
    NettyTCPServer server4 = new NettyTCPServer(serverPort4, new TestRequestHandlerFactory(3, 1, 1000, true), null);

    Thread t1 = new Thread(server1);
    Thread t2 = new Thread(server2);
    Thread t3 = new Thread(server3);
    Thread t4 = new Thread(server4);
    t1.start();
    t2.start();
    t3.start();
    t4.start();

    //Client setup
    ScheduledExecutorService timedExecutor = new ScheduledThreadPoolExecutor(1);
    ExecutorService service = new ThreadPoolExecutor(1, 1, 1, TimeUnit.DAYS, new LinkedBlockingDeque<Runnable>());
    EventLoopGroup eventLoopGroup = new NioEventLoopGroup();
    NettyClientMetrics clientMetrics = new NettyClientMetrics(registry, "client_");
    PooledNettyClientResourceManager rm =
        new PooledNettyClientResourceManager(eventLoopGroup, new HashedWheelTimer(), clientMetrics);
    KeyedPoolImpl<ServerInstance, NettyClientConnection> pool =
        new KeyedPoolImpl<ServerInstance, NettyClientConnection>(1, 1, 300000, 1, rm, timedExecutor, service, registry);
    rm.setPool(pool);

    SegmentIdSet pg1 = new SegmentIdSet();
    pg1.addSegment(new SegmentId("0"));
    SegmentIdSet pg2 = new SegmentIdSet();
    pg2.addSegment(new SegmentId("1"));
    SegmentIdSet pg3 = new SegmentIdSet();
    pg3.addSegment(new SegmentId("2"));
    SegmentIdSet pg4 = new SegmentIdSet();
    pg4.addSegment(new SegmentId("3"));

    ServerInstance serverInstance1 = new ServerInstance("localhost", serverPort1);
    ServerInstance serverInstance2 = new ServerInstance("localhost", serverPort2);
    ServerInstance serverInstance3 = new ServerInstance("localhost", serverPort3);
    ServerInstance serverInstance4 = new ServerInstance("localhost", serverPort4);

    List<ServerInstance> instances1 = new ArrayList<ServerInstance>();
    instances1.add(serverInstance1);
    List<ServerInstance> instances2 = new ArrayList<ServerInstance>();
    instances2.add(serverInstance2);
    List<ServerInstance> instances3 = new ArrayList<ServerInstance>();
    instances3.add(serverInstance3);
    List<ServerInstance> instances4 = new ArrayList<ServerInstance>();
    instances4.add(serverInstance4);
    Map<SegmentIdSet, List<ServerInstance>> pgMap = new HashMap<SegmentIdSet, List<ServerInstance>>();
    pgMap.put(pg1, instances1);
    pgMap.put(pg2, instances2);
    pgMap.put(pg3, instances3);
    pgMap.put(pg4, instances4);

    String request1 = "request_0";
    String request2 = "request_1";
    String request3 = "request_2";
    String request4 = "request_3";

    Map<SegmentIdSet, String> pgMapStr = new HashMap<SegmentIdSet, String>();
    pgMapStr.put(pg1, request1);
    pgMapStr.put(pg2, request2);
    pgMapStr.put(pg3, request3);
    pgMapStr.put(pg4, request4);

    ScatterGatherRequest req =
        new TestScatterGatherRequest(pgMap, pgMapStr, new RoundRobinReplicaSelection(),
            ReplicaSelectionGranularity.SEGMENT_ID_SET, 0, 1000);
    ScatterGatherImpl scImpl = new ScatterGatherImpl(pool);
    CompositeFuture<ServerInstance, ByteBuf> fut = scImpl.scatterGather(req);
    Map<ServerInstance, ByteBuf> v = fut.get();

    //Only 3 servers return value.
    AssertJUnit.assertEquals(3, v.size());
    ByteBuf b = v.get(serverInstance1);
    byte[] b2 = new byte[b.readableBytes()];
    b.readBytes(b2);
    String response = new String(b2);
    AssertJUnit.assertEquals("response_0_0", response);
    b = v.get(serverInstance2);
    b2 = new byte[b.readableBytes()];
    b.readBytes(b2);
    response = new String(b2);
    AssertJUnit.assertEquals("response_1_0", response);
    b = v.get(serverInstance3);
    b2 = new byte[b.readableBytes()];
    b.readBytes(b2);
    response = new String(b2);
    AssertJUnit.assertEquals("response_2_0", response);

    //No  response from 4th server
    AssertJUnit.assertNull("No response from 4th server", v.get(serverInstance4));

    Map<ServerInstance, Throwable> errorMap = fut.getError();
    AssertJUnit.assertEquals("One error", 1, errorMap.size());
    AssertJUnit.assertNotNull("Server4 returned timeout", errorMap.get(serverInstance4));
    System.out.println("Error is :" + errorMap.get(serverInstance4));

    server1.shutdownGracefully();
    server2.shutdownGracefully();
    server3.shutdownGracefully();
    server4.shutdownGracefully();
    System.out.println("Pool Stats :" + pool.getStats());

    pool.shutdown();
    service.shutdown();
    eventLoopGroup.shutdownGracefully();
    pool.getStats().refresh();

    AssertJUnit.assertEquals("Total Bad destroyed", 1, pool.getStats().getTotalBadDestroyed());

  }

  public static class TestRequestHandlerFactory implements RequestHandlerFactory {
    public final int _numRequests;
    public final int _id;
    private final long _sleepTimeMS;
    private final boolean _throwError;

    public TestRequestHandlerFactory(int id, int numRequests) {
      _id = id;
      _numRequests = numRequests;
      _sleepTimeMS = 0;
      _throwError = false;
    }

    public TestRequestHandlerFactory(int id, int numRequests, long sleepMS, boolean throwError) {
      _id = id;
      _numRequests = numRequests;
      _sleepTimeMS = sleepMS;
      _throwError = throwError;
    }

    @Override
    public RequestHandler createNewRequestHandler() {
      List<String> responses = new ArrayList<String>();
      for (int i = 0; i < _numRequests; i++) {
        responses.add("response_" + _id + "_" + i);
      }
      return new TestRequestHandler(responses, _sleepTimeMS, _throwError);
    }

    @Override
    public void init(QueryExecutor queryExecutor) {
      // TODO Auto-generated method stub

    }
  }

  public static class TestRequestHandler implements RequestHandler {
    private final List<String> _responses;
    private final List<String> _request;
    private final long _sleepTimeMS;
    private final boolean _throwError;

    private final AtomicInteger _index = new AtomicInteger(-1);

    public TestRequestHandler(List<String> responses, long sleepTimeMS, boolean throwError) {
      _responses = responses;
      _request = new ArrayList<String>();
      _sleepTimeMS = sleepTimeMS;
      _throwError = throwError;
    }

    @Override
    public byte[] processRequest(ByteBuf request) {

      if (_sleepTimeMS > 0) {
        try {
          Thread.sleep(_sleepTimeMS);
        } catch (InterruptedException e) {
        }
      }

      if (_throwError) {
        throw new RuntimeException("Dummy exception from processRequest()");
      }

      byte[] dst = new byte[request.readableBytes()];
      request.getBytes(0, dst);
      _request.add(new String(dst));
      int index = _index.incrementAndGet();
      String res = _responses.get(index);
      return res.getBytes();
    }

    public List<String> getRequest() {
      return _request;
    }
  }

  public static class TestScatterGatherRequest implements ScatterGatherRequest {
    private final Map<SegmentIdSet, List<ServerInstance>> _partitionServicesMap;
    private final Map<SegmentIdSet, String> _responsesMap;
    private final ReplicaSelection _replicaSelection;
    private final ReplicaSelectionGranularity _granularity;
    private final int _numSpeculativeRequests;
    private final int _timeoutMS;

    public TestScatterGatherRequest(Map<SegmentIdSet, List<ServerInstance>> partitionServicesMap,
        Map<SegmentIdSet, String> responsesMap) {
      _partitionServicesMap = partitionServicesMap;
      _responsesMap = responsesMap;
      _replicaSelection = new MyReplicaSelection();
      _granularity = ReplicaSelectionGranularity.SEGMENT_ID_SET;
      _numSpeculativeRequests = 0;
      _timeoutMS = 10000;
    }

    public TestScatterGatherRequest(Map<SegmentIdSet, List<ServerInstance>> partitionServicesMap,
        Map<SegmentIdSet, String> responsesMap, ReplicaSelection replicaSelection,
        ReplicaSelectionGranularity granularity, int numSpeculativeRequests, int timeoutMS) {
      _partitionServicesMap = partitionServicesMap;
      _responsesMap = responsesMap;
      _replicaSelection = replicaSelection;
      _granularity = granularity;
      _numSpeculativeRequests = numSpeculativeRequests;
      _timeoutMS = timeoutMS;
    }

    @Override
    public Map<SegmentIdSet, List<ServerInstance>> getSegmentsServicesMap() {
      return _partitionServicesMap;
    }

    @Override
    public byte[] getRequestForService(ServerInstance service, SegmentIdSet queryPartitions) {
      String s = _responsesMap.get(queryPartitions);
      return s.getBytes();
    }

    @Override
    public ReplicaSelection getReplicaSelection() {
      return _replicaSelection;
    }

    @Override
    public ReplicaSelectionGranularity getReplicaSelectionGranularity() {
      return _granularity;
    }

    @Override
    public Object getHashKey() {
      return null;
    }

    @Override
    public int getNumSpeculativeRequests() {
      return _numSpeculativeRequests;
    }

    @Override
    public BucketingSelection getPredefinedSelection() {
      return null;
    }

    @Override
    public String toString() {
      return "TestScatterGatherRequest [_partitionServicesMap=" + _partitionServicesMap + "]";
    }

    @Override
    public long getRequestTimeoutMS() {
      return _timeoutMS;
    }

    @Override
    public long getRequestId() {
      return 1L;
    }

  }

  public static class MyReplicaSelection extends ReplicaSelection {

    @Override
    public void reset(SegmentId p) {
    }

    @Override
    public void reset(SegmentIdSet p) {
    }

    @Override
    public ServerInstance selectServer(SegmentId p, List<ServerInstance> orderedServers, Object hashKey) {
      System.out.println("Partition :" + p + ", Ordered Servers :" + orderedServers);
      return orderedServers.get(0);
    }
  }
}