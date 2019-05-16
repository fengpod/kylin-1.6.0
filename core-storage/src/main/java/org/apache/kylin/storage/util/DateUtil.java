package org.apache.kylin.storage.util;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

public class DateUtil {
    //默认日期格式
    public static String DEFAULT_FORMAT = "yyyyMMdd";

    //获取当年的第一天
    public static Integer getFirstDayOfYear(int year){
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(Calendar.YEAR, year);
        Date date = calendar.getTime();
        return formatDate(date);
    }

    //获取当年的最后一天
    public static Integer getLastDayOfYear(int year){
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(Calendar.YEAR, year);
        calendar.roll(Calendar.DAY_OF_YEAR, -1);
        Date date = calendar.getTime();
        return formatDate(date);
    }

    //获取指定年、月的第一天
    public static Integer getFirstDayOfMonth(int year, int month) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        //月的范围0 - 11
        calendar.set(year, month - 1, 1);
        Date date = calendar.getTime();
        return formatDate(date);
    }

    //获取指定年、月的最后一天
    public static Integer getLastDayOfMonth(int year, int month) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        //月的范围0 - 11
        calendar.set(year, month - 1, 1);
        calendar.roll(Calendar.DATE, -1);
        Date date = calendar.getTime();
        return formatDate(date);
    }

    //获取指定年、季度的第一天
    public static Integer getFirstDayOfQuarter(int year, int quarter) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        int month;
        switch (quarter){
            case 1: month = 1; break;
            case 2: month = 4; break;
            case 3: month = 7; break;
            case 4: month = 10; break;
            default: month = 1;
        }
        return getFirstDayOfMonth(year, month);
    }

    //获取指定年、季度的最后一天
    public static Integer getLastDayOfQuarter(int year, int quarter) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        int month;
        switch (quarter){
            case 1: month = 3; break;
            case 2: month = 6; break;
            case 3: month = 9; break;
            case 4: month = 12; break;
            default: month = 3;
        }
        return getLastDayOfMonth(year, month);
    }

    //获取指定日期所在周的第一天
    public static Integer getFirstDayOfWeek(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, day);
        int dayofweek = calendar.get(Calendar.DAY_OF_WEEK);
        if (dayofweek == 1) {
            dayofweek += 7;
        }
        calendar.add(Calendar.DATE, 2 - dayofweek);
        Date date = calendar.getTime();
        return formatDate(date);
    }

    //获取指定日期所在周的最后一天
    public static Integer getLastDayOfWeek(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, day);
        int dayofweek = calendar.get(Calendar.DAY_OF_WEEK);
        if (dayofweek == 1) {
            dayofweek += 7;
        }
        calendar.add(Calendar.DATE, 8 - dayofweek);
        Date date = calendar.getTime();
        return formatDate(date);
    }

    //日期转字符串
    public static Integer formatDate(Date date){
        SimpleDateFormat f = new SimpleDateFormat(DEFAULT_FORMAT);
        return Integer.parseInt(f.format(date));
    }

    public static void main(String[] argv){
        Integer firstDay = getFirstDayOfWeek(2018,12,3);
        Integer lastDay = getLastDayOfWeek(2018,12,3);
        firstDay = getFirstDayOfWeek(2018,12,4);
        lastDay = getLastDayOfWeek(2018,12,4);
        firstDay = getFirstDayOfWeek(2018,12,5);
        lastDay = getLastDayOfWeek(2018,12,5);
        System.out.print("hello");
    }
}
