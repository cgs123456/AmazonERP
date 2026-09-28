package com.amz.scheduler;

import com.amz.lock.DistributedJobLock;
import com.amz.service.ListingCopyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Listing Feed 状态轮询调度器。
 * <p>
 * 每次只扫描一批已到期任务，每个任务发起一次状态查询后立即返回，
 * 未完成的任务由数据库中的 {@code next_poll_time} 安排下一次执行。
 * 多实例部署时使用 Redis 分布式锁保证同一时刻只有一轮扫描在运行。
 */
@Slf4j
@Component
public class ListingCopyPollingScheduler {

    private static final String LOCK_KEY = "amz:sched:listing-copy-feed-poll";
    private static final long LOCK_LEASE_SECONDS = 300L;

    @Autowired
    private ListingCopyService listingCopyService;

    @Autowired
    private DistributedJobLock distributedJobLock;

    @Value("${listing-copy.poll.enabled:true}")
    private boolean pollEnabled = true;

    @Value("${listing-copy.poll.batch-size:20}")
    private int batchSize = 20;

    @Scheduled(fixedDelayString = "${listing-copy.poll.scheduler-delay-ms:15000}")
    public void pollDueFeedTasks() {
        if (!pollEnabled) {
            log.debug("Listing Feed polling disabled, skip");
            return;
        }
        try {
            Integer processed = distributedJobLock.runWithLock(
                    LOCK_KEY,
                    LOCK_LEASE_SECONDS,
                    () -> listingCopyService.pollDueFeedTasks(batchSize),
                    0);
            if (processed != null && processed > 0) {
                log.info("Listing Feed polling processed {} task(s)", processed);
            }
        } catch (Exception e) {
            // 定时任务必须吞掉单轮异常，否则调度线程会停止后续执行。
            log.error("Listing Feed polling failed", e);
        }
    }
}
