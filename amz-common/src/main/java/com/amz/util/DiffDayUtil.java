package com.amz.util;

import java.util.Calendar;
import java.util.Date;

/**
 * 日期天数差工具类
 */
public class DiffDayUtil {

    public static int diffDays(Date date1, Date date2) {
        // 确保 date1 是较早的日期
        if (date1.after(date2)) {
            Date temp = date1;
            date1 = date2;
            date2 = temp;
        }
        
        Calendar cal1 = Calendar.getInstance();
        cal1.setTime(date1);

        Calendar cal2 = Calendar.getInstance();
        cal2.setTime(date2);

        int day1 = cal1.get(Calendar.DAY_OF_YEAR);
        int day2 = cal2.get(Calendar.DAY_OF_YEAR);

        int year1 = cal1.get(Calendar.YEAR);
        int year2 = cal2.get(Calendar.YEAR);

        if(year1 != year2) {
            int timeDistance = 0 ;
            for(int i = year1 ; i < year2 ; i++) {
                if(i % 4 == 0 && i % 100 != 0 || i % 400 == 0) {
                    timeDistance += 366;
                }
                else {
                    timeDistance += 365;
                }
            }
            return timeDistance + (day2-day1) ;
        } else {
            return day2-day1;
        }
    }
}
