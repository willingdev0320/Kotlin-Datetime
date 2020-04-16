/*
 * Copyright 2016-2020 JetBrains s.r.o.
 * Use of this source code is governed by the Apache 2.0 License that can be found in the LICENSE.txt file.
 */
/* This file implements the functions specified in `cdate.h` for Windows. */
#if DATETIME_TARGET_WIN32
/* only Windows 8 and later is supported. This is needed for
   `EnumDynamicTimeZoneInformation` to be available. */
#define _WIN32_WINNT _WIN32_WINNT_WIN8
// avoid bloat from including `Windows.h`.
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif

#include <Windows.h>
#include <Timezoneapi.h>
#include <stdexcept>
#include <string>
#include <cstring>
#include <set>
#ifdef DEBUG
#include <iostream>
#endif
#include <chrono>
#include <shared_mutex>
#include <mutex>
#include "date/date.h"
#include "helper_macros.hpp"
#include "windows_zones.hpp"

/* The maximum length of the registry key name for timezones. Taken from
   https://docs.microsoft.com/en-us/windows/win32/api/timezoneapi/ns-timezoneapi-dynamic_time_zone_information
   */
#define MAX_KEY_LENGTH 128

// The amount of time the cache is considered up-to-date.
#define CACHE_INVALIDATION_TIMEOUT std::chrono::minutes(5)

/* Taken from the `date` library, function `getTimeZoneKeyName()`.
   Gets the `std::string` representation of a time zone registry key name.
   Throws if the registry key is malformed and has a key longer than
   `MAX_KEY_LENGTH` */
static std::string key_to_string(const DYNAMIC_TIME_ZONE_INFORMATION& dtzi) {
    auto wlen = wcslen(dtzi.TimeZoneKeyName);
    char buf[MAX_KEY_LENGTH] = {};
    if (sizeof(buf) < wlen+1) {
        // Can only happen if something is terribly broken.
        throw std::runtime_error("Anomalously long timezone registry key");
    }
    wcstombs(buf, dtzi.TimeZoneKeyName, wlen);
    if (strcmp(buf, "Coordinated Universal Time") == 0)
        return "UTC";
    // Allocates std::string.
    return buf;
}

/* Returns a standard timezone name given a Windows registry key name.
   The returned C string is guaranteed to have static lifetime. */
static const char *native_name_to_standard_name(const std::string& native) {
    // inspired by `native_to_standard_timezone_name` from the `date` library
    if (native == "UTC") {
        // string literals have static lifetime.
        return "Etc/UTC";
    }
    try {
        /* `windows_to_standard` is immutable, so its contents can't
           become invalidated. */
        return windows_to_standard.at(native).c_str();
    } catch (std::out_of_range e) {
        return nullptr;
    }
}

// The next time the timezone cache should be flushed.
static std::chrono::time_point<std::chrono::steady_clock>
    next_flush = std::chrono::steady_clock::now();
// The timezone cache. Access to it should be guarded with `cache_rwlock`.
static std::unordered_map<
    std::string, DYNAMIC_TIME_ZONE_INFORMATION> cache;
// The read-write lock guarding access to the cache.
static std::shared_mutex cache_rwlock;

// Updates the timezone cache if it's time to do so.
static void repopulate_timezone_cache(
    std::chrono::time_point<std::chrono::steady_clock> current_time)
{
    const std::lock_guard<std::shared_mutex> lock(cache_rwlock);
    if (current_time < next_flush) {
        return;
    }
    cache.clear();
    DYNAMIC_TIME_ZONE_INFORMATION dtzi{};
    next_flush = current_time + CACHE_INVALIDATION_TIMEOUT;
    for (DWORD dwResult = 0, i = 0; dwResult != ERROR_NO_MORE_ITEMS; ++i) {
        dwResult = EnumDynamicTimeZoneInformation(i, &dtzi);
        if (dwResult == ERROR_SUCCESS) {
            cache[key_to_string(dtzi)] = dtzi;
        }
    }
}

/* Populates `dtzi` with the time zone information for Windows registry key
   `native_name`. Throws `std::out_of_range` if the name is invalid. */
static void time_zone_by_native_name(
    const std::string& native_name, DYNAMIC_TIME_ZONE_INFORMATION& dtzi)
{
    const auto current_time = std::chrono::steady_clock::now();
    if (current_time > next_flush) {
        repopulate_timezone_cache(current_time);
    }
    const std::shared_lock<std::shared_mutex> lock(cache_rwlock);
    dtzi = cache.at(native_name);
}

/* Populates `dtzi` with the time zone information for standard timezone name
   `name`. Returns `false` if the name is invalid. */
static bool time_zone_by_name(
    const char *name, DYNAMIC_TIME_ZONE_INFORMATION& dtzi)
{
    try {
        auto& native_name = standard_to_windows.at(name);
        time_zone_by_native_name(native_name, dtzi);
        return true;
    } catch (std::out_of_range e) {
        return false;
    }
}

/* this code is explained at
https://docs.microsoft.com/en-us/windows/win32/api/timezoneapi/ns-timezoneapi-time_zone_information
in the section about `StandardDate`.
In short, the `StandardDate` structure uses `SYSTEMTIME` in a...
non-conventional way. This function translates that representation to one
representing a proper date at a given year.
*/
static void get_transition_date(int year, const SYSTEMTIME& src, SYSTEMTIME& dst)
{
    dst = src;
    // if the year is not 0, this is the absolute time.
    if (src.wYear != 0) {
        return;
    }
    /* otherwise, the transition happens yearly at the specified month, hour,
       and minute at the specified day of the week. */
    dst.wYear = year;
    // The number of the occurrence of the specified day of week in the month,
    // or the special value "5" to denote the last such occurrence.
    unsigned int dowOccurrenceNumber = src.wDay;
    // lastly, we find the real date that corresponds to the nth occurrence.
    date::sys_days days = dowOccurrenceNumber == 5 ?
        date::sys_days(
        date::year_month_weekday_last(date::year(year), date::month(src.wMonth),
            date::weekday_last{date::weekday{src.wDayOfWeek}})) :
        date::sys_days(
        date::year_month_weekday(date::year(year), date::month(src.wMonth),
            date::weekday_indexed{
                date::weekday{src.wDayOfWeek}, dowOccurrenceNumber}));
    auto date = date::year_month_day(days);
    dst.wDay = (unsigned int)date.day();
}

#ifdef DEBUG
static void printSystime(const SYSTEMTIME& time)
{
    std::cout << time.wYear << "/" << time.wMonth << "/" <<
        time.wDay << " (" << time.wDayOfWeek << ") " <<
        time.wHour << ":" << time.wMinute << ":" << time.wSecond;
}
#endif

#define SECS_BETWEEN_1601_1970 11644473600LL
#define WINDOWS_TICKS_PER_SEC 10000000

static void unix_time_to_systemtime(int64_t epoch_sec, SYSTEMTIME& systime)
{
    int64_t windows_ticks = (epoch_sec + SECS_BETWEEN_1601_1970)
        * WINDOWS_TICKS_PER_SEC;
    ULARGE_INTEGER li;
    li.QuadPart = windows_ticks;
    FILETIME ft { li.LowPart, li.HighPart };
    FileTimeToSystemTime(&ft, &systime);
}

static int64_t systemtime_to_ticks(const SYSTEMTIME& systime)
{
    FILETIME ft {0, 0};
    SystemTimeToFileTime(&systime, &ft);
    ULARGE_INTEGER li;
    li.LowPart = ft.dwLowDateTime;
    li.HighPart = ft.dwHighDateTime;
    return li.QuadPart;
}

static int64_t systemtime_to_unix_time(const SYSTEMTIME& systime)
{
    return systemtime_to_ticks(systime) / WINDOWS_TICKS_PER_SEC -
        SECS_BETWEEN_1601_1970;
}

/* Checks whether the daylight saving time is in effect at the given time.
   `tzi` could be calculated here, but is passed along to avoid recomputing
   it. */
static bool is_daylight_time(
    const DYNAMIC_TIME_ZONE_INFORMATION& dtzi,
    const TIME_ZONE_INFORMATION& tzi,
    const SYSTEMTIME& time)
{
    // it means that daylight saving time is not supported at all
    if (tzi.StandardDate.wMonth == 0) {
        return false;
    }
    /* translate the "date" values stored in `tzi` into real dates of
       transitions to and from the daylight saving time. */
    SYSTEMTIME standard_local, daylight_local;
    get_transition_date(time.wYear, tzi.StandardDate, standard_local);
    get_transition_date(time.wYear, tzi.DaylightDate, daylight_local);
    /* Two things happen here:
        * All the relevant dates are converted to a number of ticks an some
          unified scale, counted in seconds. This is done so that we are able
          to easily add to and compare between dates.
        * `standard_local` and `daylight_local` are represented as dates in the
          local time that was active *just before* the transition. For example,
          `standard_local` contains the date of the transition to the standard
          time, as seen by a person that is currently on the daylight saving
          time. So, in order for the dates to be on the same scale, the biases
          that are assumed to be currently active are negated. */
    int64_t standard = systemtime_to_ticks(standard_local) /
        WINDOWS_TICKS_PER_SEC + (tzi.Bias + tzi.DaylightBias) * 60;
    int64_t daylight = systemtime_to_ticks(daylight_local) /
        WINDOWS_TICKS_PER_SEC + (tzi.Bias + tzi.StandardBias) * 60;
    int64_t time_secs = systemtime_to_ticks(time) /
        WINDOWS_TICKS_PER_SEC;
    /* Maybe `else` is never hit, but I've seen no indication of that assumption
       in the documentation. */
    if (daylight < standard) {
        // The year is |STANDARD|DAYLIGHT|STANDARD|
        return time_secs < standard && time_secs >= daylight;
    } else {
        // The year is |DAYLIGHT|STANDARD|DAYLIGHT|
        return time_secs < standard || time_secs >= daylight;
    }
}

// Get the UTC offset for a given timezone at a given time.
static int offset_at_systime(DYNAMIC_TIME_ZONE_INFORMATION& dtzi,
    const SYSTEMTIME& systime)
{
    TIME_ZONE_INFORMATION tzi{};
    bool result = GetTimeZoneInformationForYear(systime.wYear, &dtzi, &tzi);
    if (!result) {
        return INT_MAX;
    }
    auto bias = tzi.Bias;
    if (is_daylight_time(dtzi, tzi, systime)) {
        bias += tzi.DaylightBias;
    } else {
        bias += tzi.StandardBias;
    }
    return -bias * 60;
}

extern "C" {

#include "cdate.h"

char * get_system_timezone()
{
    DYNAMIC_TIME_ZONE_INFORMATION dtzi{};
    auto result = GetDynamicTimeZoneInformation(&dtzi);
    if (result == TIME_ZONE_ID_INVALID)
        return nullptr;
    auto key = key_to_string(dtzi);
    auto name = native_name_to_standard_name(key);
    if (name == nullptr) {
        return nullptr;
    } else {
        return strdup(name);
    }
}

char ** available_zone_ids()
{
    std::set<std::string> known_native_names, known_ids;
    known_ids.insert("UTC");
    DYNAMIC_TIME_ZONE_INFORMATION dtzi{};
    for (DWORD dwResult = 0, i = 0; dwResult != ERROR_NO_MORE_ITEMS; ++i) {
        dwResult = EnumDynamicTimeZoneInformation(i, &dtzi);
        if (dwResult == ERROR_SUCCESS) {
            known_native_names.insert(key_to_string(dtzi));
        }
    }
    for (auto it = standard_to_windows.begin();
        it != standard_to_windows.end(); ++it)
    {
        if (known_native_names.count(it->second)) {
            known_ids.insert(it->first);
        }
    }
    char ** zones = (char **)malloc(sizeof(char *) * (known_ids.size() + 1));
    zones[known_ids.size()] = nullptr;
    unsigned int i = 0;
    for (auto it = known_ids.begin(); it != known_ids.end(); ++it) {
        PUSH_BACK_OR_RETURN(zones, i, strdup(it->c_str()));
        ++i;
    }
    return zones;
}

int offset_at_instant(const char *zone_name, int64_t epoch_sec)
{
    DYNAMIC_TIME_ZONE_INFORMATION dtzi{};
    bool result = time_zone_by_name(zone_name, dtzi);
    if (!result) {
        return INT_MAX;
    }
    SYSTEMTIME systime;
    unix_time_to_systemtime(epoch_sec, systime);
    return offset_at_systime(dtzi, systime);
}

bool is_known_timezone(const char *zone_name)
{
    DYNAMIC_TIME_ZONE_INFORMATION dtzi{};
    return time_zone_by_name(zone_name, dtzi);
}

int offset_at_datetime(const char *zone_name, int64_t epoch_sec, int *offset)
{
    DYNAMIC_TIME_ZONE_INFORMATION dtzi{};
    bool result = time_zone_by_name(zone_name, dtzi);
    if (!result) {
        return INT_MAX;
    }
    SYSTEMTIME localtime, utctime, adjusted;
    unix_time_to_systemtime(epoch_sec, localtime);
    TzSpecificLocalTimeToSystemTimeEx(&dtzi, &localtime, &utctime);
    *offset = offset_at_systime(dtzi, utctime);
    SystemTimeToTzSpecificLocalTimeEx(&dtzi, &utctime, &adjusted);
    return (int)(systemtime_to_unix_time(adjusted) - epoch_sec);
}

}
#endif // DATETIME_TARGET_WIN32
