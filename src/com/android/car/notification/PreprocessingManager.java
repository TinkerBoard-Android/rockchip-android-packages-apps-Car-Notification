/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.car.notification;

import android.app.Notification;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Manager that groups and ranks the notifications in the notification center.
 */
class PreprocessingManager {

    private final Context mContext;
    private final PackageManager mPackageManager;

    PreprocessingManager(Context context) {
        mContext = context;
        mPackageManager = context.getPackageManager();
    }

    /**
     * Process the given notifications.
     *
     * @param notifications the notifications to be processed.
     * @param rankingMap    the ranking map for the notifications.
     * @return the processed notifications in a new list.
     */
    public List<NotificationGroup> process(
            @NonNull List<StatusBarNotification> notifications,
            @NonNull NotificationListenerService.RankingMap rankingMap) {

        return new ArrayList<>(rank(summarize(group(notifications)), rankingMap));
    }

    /**
     * Step 1: Groups notifications that have the same group key.
     * Never groups system notifications nor car emergency notifications.
     *
     * @param list list of ungrouped {@link StatusBarNotification}s.
     * @return list of grouped notifications as {@link NotificationGroup}s.
     */
    private List<NotificationGroup> group(List<StatusBarNotification> list) {
        SortedMap<String, NotificationGroup> groupedNotifications = new TreeMap<>();

        for (int i = 0; i < list.size(); i++) {
            StatusBarNotification statusBarNotification = list.get(i);
            Notification notification = statusBarNotification.getNotification();

            String groupKey = getGroupKey(statusBarNotification);
            if (groupedNotifications.containsKey(groupKey)) {
                if (notification.isGroupSummary()) {
                    groupedNotifications
                            .get(groupKey).setGroupHeaderNotification(statusBarNotification);
                } else {
                    groupedNotifications.get(groupKey).addNotification(statusBarNotification);
                }
            } else {
                NotificationGroup notificationGroup = new NotificationGroup();
                groupedNotifications.put(groupKey, notificationGroup);
                if (notification.isGroupSummary()) {
                    notificationGroup.setGroupHeaderNotification(statusBarNotification);
                } else {
                    notificationGroup.addNotification(statusBarNotification);
                }
            }
        }

        // In order for DiffUtil to work, the adapter needs a new data object each time it
        // updates, therefore wrapping the return value in a new list.
        return new ArrayList(groupedNotifications.values());
    }

    /**
     * Step 2: Add summary texts to system generated group header notifications.
     *
     * @param list list of {@link NotificationGroup}s.
     * @return list of {@link NotificationGroup}s with summary texts populated for group
     * headers. Operation is done on the list passed in; no new list is generated.
     */
    private List<NotificationGroup> summarize(List<NotificationGroup> list) {

        list.forEach(notificationGroup -> {
            if (notificationGroup.isGroup()) {

                StatusBarNotification headerNotification =
                        notificationGroup.getGroupHeaderNotification();

                if (headerNotification.getOverrideGroupKey() != null) {
                    // Add texts to a automatically generated group summary notification
                    Bundle extras = headerNotification.getNotification().extras;
                    if (!extras.containsKey(Notification.EXTRA_TITLE_BIG)
                            && extras.containsKey(Notification.EXTRA_BUILDER_APPLICATION_INFO)) {
                        ApplicationInfo appInfo =
                                extras.getParcelable(Notification.EXTRA_BUILDER_APPLICATION_INFO);
                        extras.putString(
                                Notification.EXTRA_TITLE_BIG,
                                appInfo.loadLabel(mPackageManager).toString());
                    }
                    if (!extras.containsKey(Notification.EXTRA_SUMMARY_TEXT)) {
                        String summaryString = mContext.getResources().getQuantityString(
                                R.plurals.group_notification_summary_text,
                                notificationGroup.getChildCount(),
                                notificationGroup.getChildCount());
                        extras.putString(Notification.EXTRA_SUMMARY_TEXT, summaryString);
                    }
                }
            }
        });
        return list;
    }

    /**
     * Step 3: Rank notifications according to the ranking key supplied by the notification
     */
    private static List<NotificationGroup> rank(
            List<NotificationGroup> notifications,
            NotificationListenerService.RankingMap rankingMap) {

        Collections.sort(notifications, new NotificationComparator(rankingMap));
        return notifications;
    }

    private static class NotificationComparator implements Comparator<NotificationGroup> {
        private final NotificationListenerService.RankingMap mRankingMap;

        NotificationComparator(NotificationListenerService.RankingMap rankingMap) {
            mRankingMap = rankingMap;
        }

        @Override
        public int compare(NotificationGroup left, NotificationGroup right) {
            NotificationListenerService.Ranking leftRanking =
                    new NotificationListenerService.Ranking();
            mRankingMap.getRanking(left.getNotificationForSorting().getKey(), leftRanking);

            NotificationListenerService.Ranking rightRanking =
                    new NotificationListenerService.Ranking();
            mRankingMap.getRanking(right.getNotificationForSorting().getKey(), rightRanking);

            return leftRanking.getRank() - rightRanking.getRank();
        }
    }

    /**
     * Helper method that generates a unique identifier for each grouped notification.
     */
    static String getGroupKey(StatusBarNotification statusBarNotification) {
        String groupKey = statusBarNotification.getGroup();
        if (groupKey == null) {
            // If a notification is not part of a group, use a unique identifier as the group key
            groupKey = statusBarNotification.getKey();
        } else {
            // Append the package name to the group key,
            // in case it is the default override group key (same for every package)
            groupKey += statusBarNotification.getPackageName();
        }
        return groupKey;
    }
}