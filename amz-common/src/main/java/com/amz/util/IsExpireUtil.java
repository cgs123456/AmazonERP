package com.amz.util;

import java.util.Date;

/**
 * 判断是否过期
 */
public class IsExpireUtil {

    public static boolean isExpire(Date startTime, Date endTime) {
        Date now = new Date();
        // 如果当前时间在开始时间之前，或者当前时间在结束时间之后，则已过期
        return now.before(startTime) || now.after(endTime);
    }
}
