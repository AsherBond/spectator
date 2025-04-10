/*
 * Copyright 2014-2021 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spectator.gc;

import com.netflix.spectator.api.Counter;
import com.netflix.spectator.api.Id;
import com.netflix.spectator.api.Spectator;
import com.netflix.spectator.api.Timer;
import com.netflix.spectator.impl.Preconditions;
import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.ListenerNotFoundException;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Logger to collect GC notifcation events.
 */
public final class GcLogger {

  private static final Logger LOGGER = LoggerFactory.getLogger(GcLogger.class);

  // One major GC per hour would require 168 for a week
  // One minor GC per minute would require 180 for three hours
  private static final int BUFFER_SIZE = 256;

  // Max size of old generation memory pool
  private static final AtomicLong MAX_DATA_SIZE =
    Spectator.globalRegistry().gauge("jvm.gc.maxDataSize", new AtomicLong(0L));

  // Size of old generation memory pool after a full GC
  private static final AtomicLong LIVE_DATA_SIZE =
    Spectator.globalRegistry().gauge("jvm.gc.liveDataSize", new AtomicLong(0L));

  // Incremented for any positive increases in the size of the old generation memory pool
  // before GC to after GC
  private static final Counter PROMOTION_RATE =
    Spectator.globalRegistry().counter("jvm.gc.promotionRate");

  // Incremented for the increase in the size of the young generation memory pool after one GC
  // to before the next
  private static final Counter ALLOCATION_RATE =
    Spectator.globalRegistry().counter("jvm.gc.allocationRate");

  // Incremented for any positive increases in the size of the survivor memory pool
  // before GC to after GC
  private static final Counter SURVIVOR_RATE =
          Spectator.globalRegistry().counter("jvm.gc.survivorRate");

  // Pause time due to GC event
  private static final Id PAUSE_TIME = Spectator.globalRegistry().createId("jvm.gc.pause");

  // Time spent in concurrent phases of GC
  private static final Id CONCURRENT_PHASE_TIME =
      Spectator.globalRegistry().createId("jvm.gc.concurrentPhaseTime");

  private final Lock lock = new ReentrantLock();

  private final long jvmStartTime;

  private final Map<String, CircularBuffer<GcEvent>> gcLogs;

  private long youngGenSizeAfter = 0L;

  private String youngGenPoolName = null;

  private String survivorPoolName = null;

  private String oldGenPoolName = null;

  private GcNotificationListener notifListener = null;

  private GcEventListener eventListener = null;

  /** Create a new instance. */
  public GcLogger() {
    jvmStartTime = ManagementFactory.getRuntimeMXBean().getStartTime();
    Map<String, CircularBuffer<GcEvent>> gcLogs = new HashMap<>();
    for (GarbageCollectorMXBean mbean : ManagementFactory.getGarbageCollectorMXBeans()) {
      CircularBuffer<GcEvent> buffer = new CircularBuffer<>(BUFFER_SIZE);
      gcLogs.put(mbean.getName(), buffer);
    }
    this.gcLogs = Collections.unmodifiableMap(gcLogs);

    for (MemoryPoolMXBean mbean : ManagementFactory.getMemoryPoolMXBeans()) {
      String poolName = mbean.getName();
      // For non-generational collectors the young and old gen pool names will be the
      // same
      if (HelperFunctions.isYoungGenPool(poolName)) {
        youngGenPoolName = poolName;
      }
      if (HelperFunctions.isSurvivorPool(poolName)) {
        survivorPoolName = poolName;
      }
      if (HelperFunctions.isOldGenPool(poolName)) {
        oldGenPoolName = poolName;
      }
    }
  }

  /**
   * Start collecting data about GC events.
   *
   * @param listener
   *     If not null, the listener will be called with the event objects after metrics and the
   *     log buffer is updated.
   */
  public void start(GcEventListener listener) {
    lock.lock();
    try {
      // TODO: this class has a bad mix of static fields used from an instance of the class. For now
      // this has been changed not to throw to make the dependency injection use-cases work. A
      // more general refactor of the GcLogger class is needed.
      if (notifListener != null) {
        LOGGER.warn("logger already started");
        return;
      }
      eventListener = listener;
      notifListener = new GcNotificationListener();
      for (GarbageCollectorMXBean mbean : ManagementFactory.getGarbageCollectorMXBeans()) {
        if (mbean instanceof NotificationEmitter) {
          final NotificationEmitter emitter = (NotificationEmitter) mbean;
          emitter.addNotificationListener(notifListener, null, null);
        }
      }
    } finally {
      lock.unlock();
    }
  }

  /** Stop collecting GC events. */
  public void stop() {
    lock.lock();
    try {
      Preconditions.checkState(notifListener != null, "logger has not been started");
      for (GarbageCollectorMXBean mbean : ManagementFactory.getGarbageCollectorMXBeans()) {
        if (mbean instanceof NotificationEmitter) {
          final NotificationEmitter emitter = (NotificationEmitter) mbean;
          try {
            emitter.removeNotificationListener(notifListener);
          } catch (ListenerNotFoundException e) {
            LOGGER.warn("could not remove gc listener", e);
          }
        }
      }
      notifListener = null;
    } finally {
      lock.unlock();
    }
  }

  /** Return the current set of GC events in the in-memory log. */
  public List<GcEvent> getLogs() {
    final List<GcEvent> logs = new ArrayList<>();
    for (CircularBuffer<GcEvent> buffer : gcLogs.values()) {
      logs.addAll(buffer.toList());
    }
    logs.sort(GcEvent.REVERSE_TIME_ORDER);
    return logs;
  }

  @SuppressWarnings("PMD.AvoidDeeplyNestedIfStmts")
  private void updateMetrics(String name, GcInfo info) {
    final Map<String, MemoryUsage> before = info.getMemoryUsageBeforeGc();
    final Map<String, MemoryUsage> after = info.getMemoryUsageAfterGc();

    if (oldGenPoolName != null) {
      final long oldBefore = before.get(oldGenPoolName).getUsed();
      final long oldAfter = after.get(oldGenPoolName).getUsed();
      final long delta = oldAfter - oldBefore;
      if (delta > 0L) {
        PROMOTION_RATE.increment(delta);
      }

      // Shenandoah doesn't report accurate pool sizes for pauses, all numbers are 0. Ignore
      // those updates.
      //
      // Some GCs such as G1 can reduce the old gen size as part of a minor GC. To track the
      // live data size we record the value if we see a reduction in the old gen heap size or
      // after a major GC.
      if (oldAfter > 0L && (oldAfter < oldBefore || HelperFunctions.isOldGcType(name))) {
        LIVE_DATA_SIZE.set(oldAfter);
        final long oldMaxAfter = after.get(oldGenPoolName).getMax();
        MAX_DATA_SIZE.set(oldMaxAfter);
      }
    }

    if (survivorPoolName != null) {
      final long survivorBefore = before.get(survivorPoolName).getUsed();
      final long survivorAfter = after.get(survivorPoolName).getUsed();
      final long delta = survivorAfter - survivorBefore;
      if (delta > 0L) {
        SURVIVOR_RATE.increment(delta);
      }
    }

    if (youngGenPoolName != null) {
      final long youngBefore = before.get(youngGenPoolName).getUsed();
      final long youngAfter = after.get(youngGenPoolName).getUsed();
      // Shenandoah doesn't report accurate pool sizes for pauses, all numbers are 0. Ignore
      // those updates.
      if (youngBefore > 0L) {
        final long delta = youngBefore - youngGenSizeAfter;
        youngGenSizeAfter = youngAfter;
        if (delta > 0L) {
          ALLOCATION_RATE.increment(delta);
        }
      }
    }
  }

  private void processGcEvent(GarbageCollectionNotificationInfo info) {
    GcEvent event = new GcEvent(info, jvmStartTime + info.getGcInfo().getStartTime());
    gcLogs.get(info.getGcName()).add(event);
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug(event.toString());
    }

    // Update pause timer for the action and cause...
    Id eventId = (isConcurrentPhase(info) ? CONCURRENT_PHASE_TIME : PAUSE_TIME)
      .withTag("action", info.getGcAction())
      .withTag("cause", info.getGcCause());
    Timer timer = Spectator.globalRegistry().timer(eventId);
    long duration = Math.max(1, info.getGcInfo().getDuration());
    timer.record(duration, TimeUnit.MILLISECONDS);

    // Update promotion and allocation counters
    updateMetrics(info.getGcName(), info.getGcInfo());

    // Notify an event listener if registered
    if (eventListener != null) {
      try {
        eventListener.onComplete(event);
      } catch (Exception e) {
        LOGGER.warn("exception thrown by event listener", e);
      }
    }
  }

  private boolean isConcurrentPhase(GarbageCollectionNotificationInfo info) {
    // So far the only indicator known is that the cause will be reported as "No GC"
    // when using CMS.
    //
    // For ZGC, behavior was changed in JDK17:
    // https://bugs.openjdk.java.net/browse/JDK-8265136
    //
    // For ZGC in older versions, there is no way to accurately get the amount of time
    // in STW pauses.
    //
    // For G1, a new bean was added in JDK20 to indicate time spent in concurrent
    // phases:
    // https://bugs.openjdk.org/browse/JDK-8297247
    return "No GC".equals(info.getGcCause())           // CMS
        || "G1 Concurrent GC".equals(info.getGcName()) // G1 in JDK20+
        || info.getGcName().endsWith(" Cycles");       // Shenandoah, ZGC
  }

  private class GcNotificationListener implements NotificationListener {
    @Override public void handleNotification(Notification notification, Object ref) {
      final String type = notification.getType();
      if (GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(type)) {
        CompositeData cd = (CompositeData) notification.getUserData();
        GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from(cd);
        processGcEvent(info);
      }
    }
  }
}
