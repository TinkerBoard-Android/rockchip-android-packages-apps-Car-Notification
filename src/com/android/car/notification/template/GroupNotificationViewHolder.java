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
package com.android.car.notification.template;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.widget.Button;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.android.car.notification.CarNotificationItemTouchListener;
import com.android.car.notification.CarNotificationViewAdapter;
import com.android.car.notification.NotificationClickHandlerFactory;
import com.android.car.notification.NotificationGroup;
import com.android.car.notification.R;

import java.util.ArrayList;
import java.util.List;

/**
 * ViewHolder that binds a list of notifications as a grouped notification.
 */
public class GroupNotificationViewHolder extends CarNotificationBaseViewHolder {
    private final NotificationClickHandlerFactory mClickHandlerFactory;
    private final Context mContext;
    private final View mHeaderDividerView;
    private final Button mToggleButton;
    private final View mCardView;
    private final RecyclerView mNotificationListView;
    private final CarNotificationViewAdapter mAdapter;
    private final Drawable mExpandDrawable;
    private final Drawable mCollapseDrawable;
    private final Paint mPaint;
    private final int mDividerHeight;
    private final CarNotificationHeaderView mGroupHeaderView;
    private StatusBarNotification mSummaryNotification;

    public GroupNotificationViewHolder(
            View view, NotificationClickHandlerFactory clickHandlerFactory) {
        super(view, clickHandlerFactory);
        mClickHandlerFactory = clickHandlerFactory;
        mContext = view.getContext();

        mGroupHeaderView = view.findViewById(R.id.group_header);
        mHeaderDividerView = view.findViewById(R.id.header_divider);
        mCardView = view.findViewById(R.id.column_card_view);
        mToggleButton = view.findViewById(R.id.group_toggle_button);
        mNotificationListView = view.findViewById(R.id.notification_list);

        int carAccentColor = mContext.getColor(R.color.notification_accent_color);
        mExpandDrawable = mContext.getDrawable(R.drawable.expand_more);
        mExpandDrawable.setTint(carAccentColor);
        mCollapseDrawable = mContext.getDrawable(R.drawable.expand_less);
        mCollapseDrawable.setTint(carAccentColor);

        mPaint = new Paint();
        mPaint.setColor(mContext.getColor(R.color.notification_list_divider_color));
        mDividerHeight = mContext.getResources().getDimensionPixelSize(
                R.dimen.car_list_divider_height);

        mNotificationListView.setLayoutManager(new LinearLayoutManager(mContext));
        mNotificationListView.addItemDecoration(new GroupedNotificationItemDecoration());
        ((SimpleItemAnimator) mNotificationListView.getItemAnimator())
                .setSupportsChangeAnimations(false);
        mNotificationListView.setNestedScrollingEnabled(false);
        mAdapter = new CarNotificationViewAdapter(mContext, /* isGroupNotificationAdapter= */ true);
        mAdapter.setClickHandlerFactory(clickHandlerFactory);
        mNotificationListView.addOnItemTouchListener(
                new CarNotificationItemTouchListener(view.getContext(), mAdapter));
        mNotificationListView.setAdapter(mAdapter);
    }

    /**
     * Because this view holder does not call {@link CarNotificationBaseViewHolder#bind},
     * we need to override this method.
     */
    @Override
    public StatusBarNotification getStatusBarNotification() {
        return mSummaryNotification;
    }

    /**
     * Group notification view holder is special in that it requires extra data to bind,
     * therefore the standard bind() method is no used. We are calling super.reset()
     * directly and binding the onclick listener manually because the card's on click behavior is
     * different when collapsed/expanded.
     */
    public void bind(
            NotificationGroup group, CarNotificationViewAdapter parentAdapter, boolean isExpanded) {
        super.reset();

        mSummaryNotification = group.getGroupSummaryNotification();
        mGroupHeaderView.bind(mSummaryNotification, /* isInGroup= */ false);

        mAdapter.setCarUxRestrictions(parentAdapter.getCarUxRestrictions());

        // use the same view pool with all the grouped notifications
        // to increase the number of the shared views and reduce memory cost
        // the view pool is created and stored in the root adapter
        mNotificationListView.setRecycledViewPool(parentAdapter.getViewPool());

        // expand button
        updateToggleButton(group.getChildCount(), isExpanded);
        mToggleButton.setOnClickListener(
                view -> {
                    boolean isExpanding = !isExpanded;
                    parentAdapter.setExpanded(group.getGroupKey(), isExpanding);
                    mAdapter.notifyDataSetChanged();

                    // When expanding, do not set an onClickListener on the overall card view.
                    mCardView.setOnClickListener(isExpanding
                            ? null : mClickHandlerFactory.getClickHandler(mSummaryNotification));
                });

        // notification cards
        List<NotificationGroup> list = new ArrayList<>();
        if (isExpanded) {
            // show header divider
            mHeaderDividerView.setVisibility(View.VISIBLE);

            // all child notifications
            group.getChildNotifications().forEach(notification -> {
                NotificationGroup notificationGroup = new NotificationGroup();
                notificationGroup.addNotification(notification);
                list.add(notificationGroup);
            });
        } else {
            // hide header divider
            mHeaderDividerView.setVisibility(View.GONE);

            // only show group summary notification
            NotificationGroup newGroup = new NotificationGroup();
            newGroup.addNotification(group.getGroupSummaryNotification());
            // If the group summary notification is automatically generated,
            // it does not contain a summary of the titles of the child notifications.
            // Therefore, we generate a list of the child notification titles from
            // the parent notification group, and pass them on.
            newGroup.setChildTitles(group.generateChildTitles());
            list.add(newGroup);
        }
        mAdapter.setNotifications(list);
    }

    private void updateToggleButton(int childCount, boolean isExpanded) {
        if (childCount == 0) {
            mToggleButton.setVisibility(View.GONE);
            return;
        }

        mToggleButton.setVisibility(View.VISIBLE);

        if (isExpanded) {
            mToggleButton.setCompoundDrawablesWithIntrinsicBounds(
                    mCollapseDrawable, null, null, null);
        } else {
            mToggleButton.setCompoundDrawablesWithIntrinsicBounds(
                    mExpandDrawable, null, null, null);
        }
    }

    private class GroupedNotificationItemDecoration extends RecyclerView.ItemDecoration {

        @Override
        public void onDrawOver(Canvas c, RecyclerView parent, RecyclerView.State state) {
            // not drawing the divider for the last item
            for (int i = 0; i < parent.getChildCount() - 1; i++) {
                drawDivider(c, parent.getChildAt(i));
            }
        }

        /**
         * Draws a divider under {@code container}.
         */
        private void drawDivider(Canvas c, View container) {
            int left = container.getLeft();
            int right = container.getRight();
            int bottom = container.getBottom() + mDividerHeight;
            int top = bottom - mDividerHeight;

            c.drawRect(left, top, right, bottom, mPaint);
        }
    }
}
