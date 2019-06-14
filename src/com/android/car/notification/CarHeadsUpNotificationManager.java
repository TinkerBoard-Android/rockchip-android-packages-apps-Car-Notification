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

import static com.android.car.assist.client.CarAssistUtils.isCarCompatibleMessagingNotification;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;

import androidx.annotation.VisibleForTesting;

import com.android.car.notification.template.MessageNotificationViewHolder;

import java.util.HashMap;
import java.util.Map;

/**
 * Notification Manager for heads-up notifications in car.
 */
public class CarHeadsUpNotificationManager
        implements CarUxRestrictionsManager.OnUxRestrictionsChangedListener {
    private static final String TAG = CarHeadsUpNotificationManager.class.getSimpleName();

    private final Beeper mBeeper;
    private final Context mContext;
    private final boolean mEnableNavigationHeadsup;
    private final long mDuration;
    private final long mMinDisplayDuration;
    private final long mEnterAnimationDuration;
    private final long mAlphaEnterAnimationDuration;
    private final long mExitAnimationDuration;
    private final int mNotificationHeadsUpCardMarginTop;

    private final KeyguardManager mKeyguardManager;
    private final PreprocessingManager mPreprocessingManager;
    private final WindowManager mWindowManager;
    private final LayoutInflater mInflater;
    private final NotificationClickHandlerFactory.OnNotificationClickListener mClickListener =
            (launchResult, alertEntry) -> animateOutHUN(alertEntry);

    private boolean mShouldRestrictMessagePreview;
    private NotificationClickHandlerFactory mClickHandlerFactory;
    private NotificationDataManager mNotificationDataManager;

    // key for the map is the statusbarnotification key
    private final Map<String, HeadsUpEntry> mActiveHeadsUpNotifications;
    // view that contains scrim and notification content
    protected final View mHeadsUpPanel;
    // framelayout that notification content should be added to.
    protected final FrameLayout mHeadsUpContentFrame;

    public CarHeadsUpNotificationManager(Context context,
            NotificationClickHandlerFactory clickHandlerFactory,
            NotificationDataManager notificationDataManager) {
        mContext = context.getApplicationContext();
        mEnableNavigationHeadsup =
                context.getResources().getBoolean(R.bool.config_showNavigationHeadsup);
        mClickHandlerFactory = clickHandlerFactory;
        mNotificationDataManager = notificationDataManager;
        mBeeper = new Beeper(mContext);
        mDuration = mContext.getResources().getInteger(R.integer.headsup_notification_duration_ms);
        mNotificationHeadsUpCardMarginTop = (int) mContext.getResources().getDimension(
                R.dimen.headsup_notification_top_margin);
        mMinDisplayDuration = mContext.getResources().getInteger(
                R.integer.heads_up_notification_minimum_time);
        mEnterAnimationDuration =
                mContext.getResources().getInteger(R.integer.headsup_total_enter_duration_ms);
        mAlphaEnterAnimationDuration =
                mContext.getResources().getInteger(R.integer.headsup_alpha_enter_duration_ms);
        mExitAnimationDuration =
                mContext.getResources().getInteger(R.integer.headsup_exit_duration_ms);
        mKeyguardManager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        mPreprocessingManager = PreprocessingManager.getInstance(context);
        mWindowManager =
                (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mInflater = LayoutInflater.from(mContext);
        mActiveHeadsUpNotifications = new HashMap<>();
        mHeadsUpPanel = createHeadsUpPanel();
        mHeadsUpContentFrame = mHeadsUpPanel.findViewById(R.id.headsup_content);
        addHeadsUpPanelToDisplay();
        mClickHandlerFactory.registerClickListener(mClickListener);
    }

    /**
     * Construct and return the heads up panel.
     *
     * @return view that contains R.id.headsup_content
     */
    protected View createHeadsUpPanel() {
        return mInflater.inflate(R.layout.headsup_container, null);
    }

    /**
     * Attach the heads up panel to the display
     */
    protected void addHeadsUpPanelToDisplay() {
        WindowManager.LayoutParams wrapperParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                // This type allows covering status bar and receiving touch input
                WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        wrapperParams.gravity = Gravity.TOP;
        mHeadsUpPanel.setVisibility(View.INVISIBLE);
        mWindowManager.addView(mHeadsUpPanel, wrapperParams);
    }

    /**
     * Set the Heads Up view to visible
     */
    protected void setHeadsUpVisible() {
        mHeadsUpPanel.setVisibility(View.VISIBLE);
    }

    /**
     * Show the notification as a heads-up if it meets the criteria.
     */
    public void maybeShowHeadsUp(
            AlertEntry alertEntry,
            NotificationListenerService.RankingMap rankingMap,
            Map<String, AlertEntry> activeNotifications) {
        if (!shouldShowHeadsUp(alertEntry, rankingMap)) {
            // check if this is a update to the existing notification and if it should still show
            // as a heads up or not.
            HeadsUpEntry currentActiveHeadsUpNotification = mActiveHeadsUpNotifications.get(
                    alertEntry.getKey());
            if (currentActiveHeadsUpNotification == null) {
                activeNotifications.put(alertEntry.getKey(), alertEntry);
                return;
            }
            if (CarNotificationDiff.sameNotificationKey(currentActiveHeadsUpNotification,
                    alertEntry)
                    && currentActiveHeadsUpNotification.getHandler().hasMessagesOrCallbacks()) {
                animateOutHUN(alertEntry);
            }
            activeNotifications.put(alertEntry.getKey(), alertEntry);
            return;
        }
        if (!activeNotifications.containsKey(alertEntry.getKey()) || canUpdate(alertEntry)
                || alertAgain(alertEntry.getNotification())) {
            showHeadsUp(mPreprocessingManager.optimizeForDriving(alertEntry),
                    rankingMap);
        }
        activeNotifications.put(alertEntry.getKey(), alertEntry);
    }

    /**
     * This method gets called when an app wants to cancel or withdraw its notification.
     */
    public void maybeRemoveHeadsUp(AlertEntry alertEntry) {
        HeadsUpEntry currentActiveHeadsUpNotification = mActiveHeadsUpNotifications.get(
                alertEntry.getKey());
        // if the heads up notification is already removed do nothing.
        if (currentActiveHeadsUpNotification == null) {
            return;
        }

        long totalDisplayDuration =
                System.currentTimeMillis() - currentActiveHeadsUpNotification.getPostTime();
        // ongoing notification that has passed the minimum threshold display time.
        if (totalDisplayDuration >= mMinDisplayDuration) {
            animateOutHUN(alertEntry);
            return;
        }

        long earliestRemovalTime = mMinDisplayDuration - totalDisplayDuration;

        currentActiveHeadsUpNotification.getHandler().postDelayed(() ->
                animateOutHUN(alertEntry), earliestRemovalTime);
    }

    /**
     * Returns true if the notification's flag is not set to
     * {@link Notification#FLAG_ONLY_ALERT_ONCE}
     */
    private boolean alertAgain(Notification newNotification) {
        return (newNotification.flags & Notification.FLAG_ONLY_ALERT_ONCE) == 0;
    }

    /**
     * Return true if the currently displaying notification have the same key as the new added
     * notification. In that case it will be considered as an update to the currently displayed
     * notification.
     */
    private boolean isUpdate(AlertEntry alertEntry) {
        HeadsUpEntry currentActiveHeadsUpNotification = mActiveHeadsUpNotifications.get(
                alertEntry.getKey());
        if (currentActiveHeadsUpNotification == null) {
            return false;
        }
        return CarNotificationDiff.sameNotificationKey(currentActiveHeadsUpNotification,
                alertEntry);
    }

    /**
     * Updates only when the notification is being displayed.
     */
    private boolean canUpdate(AlertEntry alertEntry) {
        HeadsUpEntry currentActiveHeadsUpNotification = mActiveHeadsUpNotifications.get(
                alertEntry.getKey());
        return currentActiveHeadsUpNotification != null && System.currentTimeMillis() -
                currentActiveHeadsUpNotification.getPostTime() < mDuration;
    }

    /**
     * Returns the active headsUpEntry or creates a new one while adding it to the list of
     * mActiveHeadsUpNotifications.
     */
    private HeadsUpEntry addNewHeadsUpEntry(AlertEntry alertEntry) {
        HeadsUpEntry currentActiveHeadsUpNotification = mActiveHeadsUpNotifications.get(
                alertEntry.getKey());
        if (currentActiveHeadsUpNotification == null) {
            currentActiveHeadsUpNotification = new HeadsUpEntry(
                    alertEntry.getStatusBarNotification());
            mActiveHeadsUpNotifications.put(alertEntry.getKey(),
                    currentActiveHeadsUpNotification);
            currentActiveHeadsUpNotification.isAlertAgain = alertAgain(
                    alertEntry.getNotification());
            currentActiveHeadsUpNotification.isNewHeadsUp = true;
            return currentActiveHeadsUpNotification;
        }
        currentActiveHeadsUpNotification.isNewHeadsUp = false;
        currentActiveHeadsUpNotification.isAlertAgain = alertAgain(
                alertEntry.getNotification());
        if (currentActiveHeadsUpNotification.isAlertAgain) {
            // This is a ongoing notification which needs to be alerted again to the user. This
            // requires for the post time to be updated.
            currentActiveHeadsUpNotification.updatePostTime();
        }
        return currentActiveHeadsUpNotification;
    }

    /**
     * Controls three major conditions while showing heads up notification.
     * <p>
     * <ol>
     * <li> When a new HUN comes in it will be displayed with animations
     * <li> If an update to existing HUN comes in which enforces to alert the HUN again to user,
     * then the post time will be updated to current time. This will only be done if {@link
     * Notification#FLAG_ONLY_ALERT_ONCE} flag is not set.
     * <li> If an update to existing HUN comes in which just updates the data and does not want to
     * alert itself again, then the animations will not be shown and the data will get updated. This
     * will only be done if {@link Notification#FLAG_ONLY_ALERT_ONCE} flag is not set.
     * </ol>
     */
    private void showHeadsUp(AlertEntry alertEntry,
            NotificationListenerService.RankingMap rankingMap) {
        // Show animations only when there is no active HUN and notification is new. This check
        // needs to be done here because after this the new notification will be added to the map
        // holding ongoing notifications.
        boolean shouldShowAnimation = !isUpdate(alertEntry);
        HeadsUpEntry currentNotification = addNewHeadsUpEntry(alertEntry);
        if (currentNotification.isNewHeadsUp) {
            playSound(alertEntry, rankingMap);
            setHeadsUpVisible();
            setAutoDismissViews(currentNotification, alertEntry);
        } else if (currentNotification.isAlertAgain) {
            setAutoDismissViews(currentNotification, alertEntry);
        }
        CarNotificationTypeItem notificationTypeItem = getNotificationViewType(alertEntry);
        currentNotification.setClickHandlerFactory(mClickHandlerFactory);

        if (currentNotification.getNotificationView() == null) {
            currentNotification.setNotificationView(mInflater.inflate(
                    notificationTypeItem.getHeadsUpTemplate(),
                    null));
            mHeadsUpContentFrame.addView(currentNotification.getNotificationView());
            currentNotification.setViewHolder(
                    notificationTypeItem.getViewHolder(currentNotification.getNotificationView(),
                            mClickHandlerFactory));
        }

        if (mShouldRestrictMessagePreview && notificationTypeItem.getNotificationType()
                == NotificationViewType.MESSAGE) {
            ((MessageNotificationViewHolder) currentNotification.getViewHolder())
                    .bindRestricted(alertEntry, /* isInGroup= */ false, /* isHeadsUp= */ true);
        } else {
            currentNotification.getViewHolder().bind(alertEntry, /* isInGroup= */false,
                    /* isHeadsUp= */ true);
        }

        // measure the size of the card and make that area of the screen touchable
        currentNotification.getNotificationView().getViewTreeObserver()
                .addOnComputeInternalInsetsListener(
                        info -> {
                            // If the panel is not on screen don't modify the touch region
                            if (mHeadsUpPanel.getVisibility() != View.VISIBLE) return;
                            int[] mTmpTwoArray = new int[2];
                            View cardView = currentNotification.getNotificationView().findViewById(
                                    R.id.card_view);
                            if (cardView == null) return;
                            cardView.getLocationOnScreen(mTmpTwoArray);
                            int minX = mTmpTwoArray[0];
                            int maxX = mTmpTwoArray[0] + cardView.getWidth();
                            int height = cardView.getHeight();
                            info.setTouchableInsets(
                                    ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
                            info.touchableRegion.set(minX, mNotificationHeadsUpCardMarginTop, maxX,
                                    height + mNotificationHeadsUpCardMarginTop);
                        });
        // Get the height of the notification view after onLayout()
        // in order animate the notification in
        currentNotification.getNotificationView().getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        int notificationHeight =
                                currentNotification.getNotificationView().getHeight();

                        if (shouldShowAnimation) {
                            currentNotification.getNotificationView().setY(0 - notificationHeight);
                            currentNotification.getNotificationView().setAlpha(0f);

                            Interpolator yPositionInterpolator = AnimationUtils.loadInterpolator(
                                    mContext,
                                    R.interpolator.heads_up_entry_direction_interpolator);
                            Interpolator alphaInterpolator = AnimationUtils.loadInterpolator(
                                    mContext,
                                    R.interpolator.heads_up_entry_alpha_interpolator);

                            ObjectAnimator moveY = ObjectAnimator.ofFloat(
                                    currentNotification.getNotificationView(), "y", 0f);
                            moveY.setDuration(mEnterAnimationDuration);
                            moveY.setInterpolator(yPositionInterpolator);

                            ObjectAnimator alpha = ObjectAnimator.ofFloat(
                                    currentNotification.getNotificationView(), "alpha", 1f);
                            alpha.setDuration(mAlphaEnterAnimationDuration);
                            alpha.setInterpolator(alphaInterpolator);

                            AnimatorSet animatorSet = new AnimatorSet();
                            animatorSet.playTogether(moveY, alpha);
                            animatorSet.start();

                        }
                        currentNotification.getNotificationView().getViewTreeObserver()
                                .removeOnGlobalLayoutListener(this);
                    }
                });

        if (currentNotification.isNewHeadsUp) {
            boolean shouldDismissOnSwipe = true;
            if (shouldDismissOnSwipe(alertEntry)) {
                shouldDismissOnSwipe = false;
            }
            // Add swipe gesture
            View cardView = currentNotification.getNotificationView().findViewById(R.id.card_view);
            cardView.setOnTouchListener(
                    new HeadsUpNotificationOnTouchListener(cardView, shouldDismissOnSwipe,
                            () -> resetView(alertEntry)));
        }
    }

    private void playSound(AlertEntry alertEntry,
            NotificationListenerService.RankingMap rankingMap) {
        NotificationListenerService.Ranking ranking = getRanking();
        if (rankingMap.getRanking(alertEntry.getKey(), ranking)) {
            NotificationChannel notificationChannel = ranking.getChannel();
            // If sound is not set on the notification channel and default is not chosen it
            // can be null.
            if (notificationChannel.getSound() != null) {
                // make the sound
                mBeeper.beep(alertEntry.getStatusBarNotification().getPackageName(),
                        notificationChannel.getSound());
            }
        }
    }

    private boolean shouldDismissOnSwipe(AlertEntry alertEntry) {
        return hasFullScreenIntent(alertEntry)
                && alertEntry.getNotification().category.equals(
                Notification.CATEGORY_CALL) && alertEntry.getStatusBarNotification().isOngoing();
    }


    @VisibleForTesting
    protected Map<String, HeadsUpEntry> getActiveHeadsUpNotifications() {
        return mActiveHeadsUpNotifications;
    }

    private void setAutoDismissViews(HeadsUpEntry currentNotification, AlertEntry alertEntry) {
        // Should not auto dismiss if HUN has a full screen Intent.
        if (hasFullScreenIntent(alertEntry)) {
            return;
        }
        currentNotification.getHandler().removeCallbacksAndMessages(null);
        currentNotification.getHandler().postDelayed(() -> animateOutHUN(alertEntry), mDuration);
    }

    /**
     * Returns true if AlertEntry has a full screen Intent.
     */
    private boolean hasFullScreenIntent(AlertEntry alertEntry) {
        return alertEntry.getNotification().fullScreenIntent != null;
    }

    /**
     * Animates the heads up notification out of the screen and reset the views.
     */
    private void animateOutHUN(AlertEntry alertEntry) {
        Log.d(TAG, "clearViews for Heads Up Notification: ");
        // get the current notification to perform animations and remove it immediately from the
        // active notification maps and cancel all other call backs if any.
        HeadsUpEntry currentHeadsUpNotification = mActiveHeadsUpNotifications.get(
                alertEntry.getKey());
        // view can also be removed when swipped away.
        if (currentHeadsUpNotification == null) {
            return;
        }
        currentHeadsUpNotification.getHandler().removeCallbacksAndMessages(null);

        Interpolator exitInterpolator = AnimationUtils.loadInterpolator(mContext,
                R.interpolator.heads_up_exit_direction_interpolator);
        Interpolator alphaInterpolator = AnimationUtils.loadInterpolator(mContext,
                R.interpolator.heads_up_exit_alpha_interpolator);

        ObjectAnimator moveY = ObjectAnimator.ofFloat(
                currentHeadsUpNotification.getNotificationView(), "y",
                -1 * currentHeadsUpNotification.getNotificationView().getHeight());
        moveY.setDuration(mExitAnimationDuration);
        moveY.setInterpolator(exitInterpolator);

        ObjectAnimator alpha = ObjectAnimator.ofFloat(
                currentHeadsUpNotification.getNotificationView(), "alpha", 1f);
        alpha.setDuration(mExitAnimationDuration);
        alpha.setInterpolator(alphaInterpolator);

        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(moveY, alpha);
        animatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                removeNotificationFromPanel(currentHeadsUpNotification);

                // Remove HUN after the animation ends to prevent accidental touch on the card
                // triggering another remove call.
                mActiveHeadsUpNotifications.remove(alertEntry.getKey());
            }
        });
        animatorSet.start();
    }

    /**
     * Remove notification from the screen. If it was the last notification hide the heads up panel.
     *
     * @param currentHeadsUpNotification The notification to remove
     */
    protected void removeNotificationFromPanel(HeadsUpEntry currentHeadsUpNotification) {
        mHeadsUpContentFrame.removeView(currentHeadsUpNotification.getNotificationView());
        if (mHeadsUpContentFrame.getChildCount() == 0) {
            mHeadsUpPanel.setVisibility(View.INVISIBLE);
        }
    }


    /**
     * Removes the view for the active heads up notification and also removes the HUN from the map
     * of active Notifications.
     */
    private void resetView(AlertEntry alertEntry) {
        HeadsUpEntry currentHeadsUpNotification = mActiveHeadsUpNotifications.get(
                alertEntry.getKey());
        if (currentHeadsUpNotification == null) return;

        currentHeadsUpNotification.getHandler().removeCallbacksAndMessages(null);
        removeNotificationFromPanel(currentHeadsUpNotification);
        mActiveHeadsUpNotifications.remove(alertEntry.getKey());
    }

    /**
     * Choose a correct notification layout for this heads-up notification.
     * Note that the layout chosen can be different for the same notification
     * in the notification center.
     */
    private static CarNotificationTypeItem getNotificationViewType(
            AlertEntry alertEntry) {
        String category = alertEntry.getNotification().category;
        if (category != null) {
            switch (category) {
                case Notification.CATEGORY_CAR_EMERGENCY:
                    return CarNotificationTypeItem.EMERGENCY;
                case Notification.CATEGORY_NAVIGATION:
                    return CarNotificationTypeItem.NAVIGATION;
                case Notification.CATEGORY_CALL:
                    return CarNotificationTypeItem.CALL;
                case Notification.CATEGORY_CAR_WARNING:
                    return CarNotificationTypeItem.WARNING;
                case Notification.CATEGORY_CAR_INFORMATION:
                    return CarNotificationTypeItem.INFORMATION;
                case Notification.CATEGORY_MESSAGE:
                    return CarNotificationTypeItem.MESSAGE;
                default:
                    break;
            }
        }
        Bundle extras = alertEntry.getNotification().extras;
        if (extras.containsKey(Notification.EXTRA_BIG_TEXT)
                && extras.containsKey(Notification.EXTRA_SUMMARY_TEXT)) {
            return CarNotificationTypeItem.INBOX;
        }
        // progress, media, big text, big picture, and basic templates
        return CarNotificationTypeItem.BASIC;
    }

    /**
     * Helper method that determines whether a notification should show as a heads-up.
     *
     * <p> A notification will never be shown as a heads-up if:
     * <ul>
     * <li> Keyguard (lock screen) is showing
     * <li> OEMs configured CATEGORY_NAVIGATION should not be shown
     * <li> Notification is muted.
     * </ul>
     *
     * <p> A notification will be shown as a heads-up if:
     * <ul>
     * <li> Importance >= HIGH
     * <li> it comes from an app signed with the platform key.
     * <li> it comes from a privileged system app.
     * <li> is a car compatible notification.
     * {@link com.android.car.assist.client.CarAssistUtils#isCarCompatibleMessagingNotification}
     * <li> Notification category is one of CATEGORY_CALL or CATEGORY_NAVIGATION
     * </ul>
     *
     * <p> Group alert behavior still follows API documentation.
     *
     * @return true if a notification should be shown as a heads-up
     */
    private boolean shouldShowHeadsUp(
            AlertEntry alertEntry,
            NotificationListenerService.RankingMap rankingMap) {
        if (mKeyguardManager.isKeyguardLocked()) {
            return false;
        }
        Notification notification = alertEntry.getNotification();

        // Navigation notification configured by OEM
        if (!mEnableNavigationHeadsup && Notification.CATEGORY_NAVIGATION.equals(
                notification.category)) {
            return false;
        }
        // Group alert behavior
        if (notification.suppressAlertingDueToGrouping()) {
            return false;
        }
        // Messaging notification muted by user.
        if (mNotificationDataManager.isMessageNotificationMuted(alertEntry)) {
            return false;
        }

        // Do not show if importance < HIGH
        NotificationListenerService.Ranking ranking = getRanking();
        if (rankingMap.getRanking(alertEntry.getKey(), ranking)) {
            if (ranking.getImportance() < NotificationManager.IMPORTANCE_HIGH) {
                return false;
            }
        }

        if (NotificationUtils.isSystemPrivilegedOrPlatformKey(mContext, alertEntry)) {
            return true;
        }

        // Allow car messaging type.
        if (isCarCompatibleMessagingNotification(alertEntry.getStatusBarNotification())) {
            return true;
        }

        if (notification.category == null) {
            Log.d(TAG, "category not set for: "
                    + alertEntry.getStatusBarNotification().getPackageName());
        }

        // Allow for Call, and nav TBT categories.
        if (Notification.CATEGORY_CALL.equals(notification.category)
                || Notification.CATEGORY_NAVIGATION.equals(notification.category)) {
            return true;
        }
        return false;
    }

    @VisibleForTesting
    protected NotificationListenerService.Ranking getRanking() {
        return new NotificationListenerService.Ranking();
    }

    @Override
    public void onUxRestrictionsChanged(CarUxRestrictions restrictions) {
        mShouldRestrictMessagePreview =
                (restrictions.getActiveRestrictions()
                        & CarUxRestrictions.UX_RESTRICTIONS_NO_TEXT_MESSAGE) != 0;
    }

    /**
     * Sets the source of {@link View.OnClickListener}
     *
     * @param clickHandlerFactory used to generate onClickListeners
     */
    @VisibleForTesting
    public void setClickHandlerFactory(NotificationClickHandlerFactory clickHandlerFactory) {
        mClickHandlerFactory = clickHandlerFactory;
    }
}
