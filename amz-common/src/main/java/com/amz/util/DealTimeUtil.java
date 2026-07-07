package com.amz.util;

/**
 * 处理时间工具类
 */
public class DealTimeUtil {

    public static String dealTime(Integer days) {
        String dealTime = "";
        if (days < 0) {
            dealTime = "刚刚";
        } else if (days == 0) {
            dealTime = "今天";
        } else if (days <= 3) {
            dealTime = days + "天前";
        } else {
            // 超过3天，返回具体日期
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.add(java.util.Calendar.DAY_OF_YEAR, -days);
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
            dealTime = sdf.format(cal.getTime());
        }
        return dealTime;
    }
}
