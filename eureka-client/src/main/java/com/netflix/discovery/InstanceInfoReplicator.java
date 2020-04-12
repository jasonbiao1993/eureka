package com.netflix.discovery;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.util.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A task for updating and replicating the local instanceinfo to the remote server. Properties of this task are:
 * - configured with a single update thread to guarantee sequential update to the remote server
 * - update tasks can be scheduled on-demand via onDemandUpdate()
 * - task processing is rate limited by burstSize
 * - a new update task is always scheduled automatically after an earlier update task. However if an on-demand task
 *   is started, the scheduled automatic update task is discarded (and a new one will be scheduled after the new
 *   on-demand update).
 *
 * 1.InstanceInfoReplicator是个任务类，负责将自身的信息周期性的上报到Eureka server；
 * 2.有两个场景触发上报：周期性任务、服务状态变化(onDemandUpdate被调用)，因此，在同一时刻有可能有两个上报的任务同时出现；
 * 3.单线程执行上报的操作，如果有多个上报任务，也能确保是串行的；
 * 4.有频率限制，通过burstSize参数来控制；
 * 5.先创建的任务总是先执行，但是onDemandUpdate方法中创建的任务会将周期性任务给丢弃掉；
 *   @author dliu
 */
class InstanceInfoReplicator implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(InstanceInfoReplicator.class);

    private final DiscoveryClient discoveryClient;
    private final InstanceInfo instanceInfo;

    private final int replicationIntervalSeconds;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<Future> scheduledPeriodicRef;

    private final AtomicBoolean started;
    private final RateLimiter rateLimiter;
    private final int burstSize;
    private final int allowedRatePerMinute;

    InstanceInfoReplicator(DiscoveryClient discoveryClient, InstanceInfo instanceInfo, int replicationIntervalSeconds, int burstSize) {
        this.discoveryClient = discoveryClient;
        this.instanceInfo = instanceInfo;
        this.scheduler = Executors.newScheduledThreadPool(1,
                new ThreadFactoryBuilder()
                        .setNameFormat("DiscoveryClient-InstanceInfoReplicator-%d")
                        .setDaemon(true)
                        .build());

        this.scheduledPeriodicRef = new AtomicReference<Future>();

        this.started = new AtomicBoolean(false);
        // RateLimiter是个限制频率的工具类，用来限制单位时间内的任务次数
        this.rateLimiter = new RateLimiter(TimeUnit.MINUTES);
        this.replicationIntervalSeconds = replicationIntervalSeconds;
        this.burstSize = burstSize;

        // 通过周期间隔，和burstSize参数，计算每分钟允许的任务数
        this.allowedRatePerMinute = 60 * this.burstSize / this.replicationIntervalSeconds;
        logger.info("InstanceInfoReplicator onDemand update allowed rate per min is {}", allowedRatePerMinute);
    }

    public void start(int initialDelayMs) {
        // CAS操作，不但保证了只执行一次，多线程场景也能保证
        if (started.compareAndSet(false, true)) {
            instanceInfo.setIsDirty();  // for initial register
            // 提交一个任务，延时执行，注意第一个参数是this，因此延时结束时，InstanceInfoReplicator的run方法会被执行
            Future next = scheduler.schedule(this, initialDelayMs, TimeUnit.SECONDS);
            // 这个任务的Feature对象放在成员变量scheduledPeriodicRef中
            scheduledPeriodicRef.set(next);
        }
    }

    public void stop() {
        shutdownAndAwaitTermination(scheduler);
        started.set(false);
    }

    private void shutdownAndAwaitTermination(ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.warn("InstanceInfoReplicator stop interrupted");
        }
    }

    public boolean onDemandUpdate() {
        // 限制更新
        // 没有达到频率限制才会执行
        if (rateLimiter.acquire(burstSize, allowedRatePerMinute)) {
            if (!scheduler.isShutdown()) {
                // 提交一个任务
                scheduler.submit(new Runnable() {
                    @Override
                    public void run() {
                        logger.debug("Executing on-demand update of local InstanceInfo");

                        // 取出之前已经提交的任务
                        Future latestPeriodic = scheduledPeriodicRef.get();
                        // 如果此任务未完成，就立即取消
                        if (latestPeriodic != null && !latestPeriodic.isDone()) {
                            logger.debug("Canceling the latest scheduled update, it will be rescheduled at the end of on demand update");
                            // 取消最新的计划更新，它将在按需更新结束时重新计划
                            latestPeriodic.cancel(false);
                        }

                        // 通过调用run方法，令任务在延时后执行，相当于周期性任务中的一次
                        InstanceInfoReplicator.this.run();
                    }
                });
                return true;
            } else {
                logger.warn("Ignoring onDemand update due to stopped scheduler");
                return false;
            }
        } else {
            // 如果超过了设置的频率限制，本次onDemandUpdate方法就提交任务了
            logger.warn("Ignoring onDemand update due to rate limiter");
            return false;
        }
    }

    public void run() {
        try {
            // 更新信息，用于稍后的上报
            discoveryClient.refreshInstanceInfo();

            // 死亡时间戳，存在重新注册
            Long dirtyTimestamp = instanceInfo.isDirtyWithTime();
            if (dirtyTimestamp != null) {
                // 服务注册
                // 上报
                discoveryClient.register();
                instanceInfo.unsetIsDirty(dirtyTimestamp);
            }
        } catch (Throwable t) {
            logger.warn("There was a problem with the instance info replicator", t);
        } finally {
            // 重新检查，判断是否注册
            // 每次执行完毕都会创建一个延时执行的任务，就这样实现了周期性执行的逻辑
            Future next = scheduler.schedule(this, replicationIntervalSeconds, TimeUnit.SECONDS);

            // 每次创建的周期性任务，都要放入scheduledPeriodicRef，
            // 如果外部调用了onDemandUpdate，就能通过onDemandUpdate取得当前要执行的任务
            scheduledPeriodicRef.set(next);
        }
    }

}
