/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.wm;

import static android.app.ActivityManager.StackId.DOCKED_STACK_ID;
import static android.app.ActivityManager.StackId.FREEFORM_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.HOME_STACK_ID;
import static android.content.pm.ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.app.ActivityManager.RESIZE_MODE_SYSTEM_SCREEN_ROTATION;
import static android.content.pm.ActivityInfo.RESIZE_MODE_CROP_WINDOWS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_RESIZE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STACK;
import static com.android.server.wm.WindowManagerService.H.RESIZE_TASK;
import static com.android.server.wm.WindowManagerService.H.SHOW_NON_RESIZEABLE_DOCK_TOAST;
import static android.view.WindowManager.DOCKED_INVALID;
import static android.view.WindowManager.DOCKED_LEFT;
import static android.view.WindowManager.DOCKED_RIGHT;
import static android.view.WindowManager.DOCKED_TOP;

import android.app.ActivityManager.StackId;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.util.EventLog;
import android.util.Slog;
import android.view.DisplayInfo;
import android.view.Surface;

import com.android.internal.R;
import com.android.server.EventLogTags;

import java.io.PrintWriter;
import java.util.ArrayList;

class Task implements DimLayer.DimLayerUser {
    static final String TAG = TAG_WITH_CLASS_NAME ? "Task" : TAG_WM;
    // Return value from {@link setBounds} indicating no change was made to the Task bounds.
    static final int BOUNDS_CHANGE_NONE = 0;
    // Return value from {@link setBounds} indicating the position of the Task bounds changed.
    static final int BOUNDS_CHANGE_POSITION = 1;
    // Return value from {@link setBounds} indicating the size of the Task bounds changed.
    static final int BOUNDS_CHANGE_SIZE = 1 << 1;

    TaskStack mStack;
    final AppTokenList mAppTokens = new AppTokenList();
    final int mTaskId;
    final int mUserId;
    boolean mDeferRemoval = false;
    final WindowManagerService mService;

    // Content limits relative to the DisplayContent this sits in.
    private Rect mBounds = new Rect();
    final Rect mPreparedFrozenBounds = new Rect();

    private Rect mPreScrollBounds = new Rect();
    private boolean mScrollValid;

    // Bounds used to calculate the insets.
    private final Rect mTempInsetBounds = new Rect();

    // Device rotation as of the last time {@link #mBounds} was set.
    int mRotation;

    // Whether mBounds is fullscreen
    private boolean mFullscreen = true;

    // Contains configurations settings that are different from the global configuration due to
    // stack specific operations. E.g. {@link #setBounds}.
    Configuration mOverrideConfig;

    // For comparison with DisplayContent bounds.
    private Rect mTmpRect = new Rect();
    // For handling display rotations.
    private Rect mTmpRect2 = new Rect();

    // Resize mode of the task. See {@link ActivityInfo#resizeMode}
    private int mResizeMode;

    // Whether we need to show toast about the app being non-resizeable when it becomes visible.
    // This flag is set when a non-resizeable task is docked (or side-by-side). It's cleared
    // after we show the toast.
    private boolean mShowNonResizeableDockToast;

    // Whether the task is currently being drag-resized
    private boolean mDragResizing;

    private boolean mHomeTask;

    Task(int taskId, TaskStack stack, int userId, WindowManagerService service, Rect bounds,
            Configuration config) {
        mTaskId = taskId;
        mStack = stack;
        mUserId = userId;
        mService = service;
        setBounds(bounds, config);
    }

    DisplayContent getDisplayContent() {
        return mStack.getDisplayContent();
    }

    void setShowNonResizeableDockToast() {
        mShowNonResizeableDockToast = true;
    }

    void scheduleShowNonResizeableDockToastIfNeeded() {
        if (!mShowNonResizeableDockToast) {
            return;
        }
        final DisplayContent displayContent = mStack.getDisplayContent();
        // If docked stack is not yet visible, we don't want to show the toast yet,
        // since we need the visible rect of the docked task to position the toast.
        if (displayContent == null || displayContent.getDockedStackLocked() == null) {
            return;
        }

        mShowNonResizeableDockToast = false;

        if (isResizeable()) {
            Slog.wtf(TAG,
                    "Trying to show non-resizeable toast when task is resizeable task=" + this);
            return;
        }

        if (mResizeMode == RESIZE_MODE_UNRESIZEABLE) {
            final String text =
                    mService.mContext.getString(R.string.dock_non_resizeble_failed_to_dock_text);
            mService.mH.obtainMessage(SHOW_NON_RESIZEABLE_DOCK_TOAST, 0, 0, text).sendToTarget();
            return;
        }

        final int dockSide = mStack.getDockSide();
        if (!inCropWindowsResizeMode() || dockSide == DOCKED_INVALID) {
            return;
        }

        int xOffset = 0;
        int yOffset = 0;
        mStack.getBounds(mTmpRect);

        if (dockSide == DOCKED_LEFT || dockSide == DOCKED_RIGHT) {
            // The toast was originally placed at the bottom and centered. To place it at the
            // bottom-center of the stack, we offset it horizontally by the diff between the center
            // of the stack bounds vs. the center of the screen.
            displayContent.getLogicalDisplayRect(mTmpRect2);
            xOffset = mTmpRect.centerX() - mTmpRect2.centerX();
        } else if (dockSide == DOCKED_TOP) {
            // The toast was originally placed at the bottom and centered. To place it at the bottom
            // center of the top stack, we offset it vertically by the diff between the bottom of
            // the stack bounds vs. the bottom of the content rect.
            //
            // Note here we use the content rect instead of the display rect, as we want the toast's
            // distance to the dock divider (when it's placed at the top half) to be the same as
            // it's distance to the top of the navigation bar (when it's placed at the bottom).

            // We don't adjust for DOCKED_BOTTOM case since it's already at the bottom.
            displayContent.getContentRect(mTmpRect2);
            yOffset = mTmpRect2.bottom - mTmpRect.bottom;
        }
        final String text =
                mService.mContext.getString(R.string.dock_cropped_windows_text);
        mService.mH.obtainMessage(SHOW_NON_RESIZEABLE_DOCK_TOAST,
                xOffset, yOffset, text).sendToTarget();
    }

    void addAppToken(int addPos, AppWindowToken wtoken, int resizeMode, boolean homeTask) {
        final int lastPos = mAppTokens.size();
        if (addPos >= lastPos) {
            addPos = lastPos;
        } else {
            for (int pos = 0; pos < lastPos && pos < addPos; ++pos) {
                if (mAppTokens.get(pos).removed) {
                    // addPos assumes removed tokens are actually gone.
                    ++addPos;
                }
            }
        }
        mAppTokens.add(addPos, wtoken);
        wtoken.mTask = this;
        mDeferRemoval = false;
        mResizeMode = resizeMode;
        mHomeTask = homeTask;
    }

    private boolean hasAppTokensAlive() {
        for (int i = mAppTokens.size() - 1; i >= 0; i--) {
            if (!mAppTokens.get(i).appDied) {
                return true;
            }
        }
        return false;
    }

    void removeLocked() {
        if (hasAppTokensAlive() && mStack.isAnimating()) {
            if (DEBUG_STACK) Slog.i(TAG, "removeTask: deferring removing taskId=" + mTaskId);
            mDeferRemoval = true;
            return;
        }
        if (DEBUG_STACK) Slog.i(TAG, "removeTask: removing taskId=" + mTaskId);
        EventLog.writeEvent(EventLogTags.WM_TASK_REMOVED, mTaskId, "removeTask");
        mDeferRemoval = false;
        DisplayContent content = getDisplayContent();
        if (content != null) {
            content.mDimLayerController.removeDimLayerUser(this);
        }
        mStack.removeTask(this);
        mService.mTaskIdToTask.delete(mTaskId);
    }

    void moveTaskToStack(TaskStack stack, boolean toTop) {
        if (stack == mStack) {
            return;
        }
        if (DEBUG_STACK) Slog.i(TAG, "moveTaskToStack: removing taskId=" + mTaskId
                + " from stack=" + mStack);
        EventLog.writeEvent(EventLogTags.WM_TASK_REMOVED, mTaskId, "moveTask");
        if (mStack != null) {
            mStack.removeTask(this);
        }
        stack.addTask(this, toTop);
    }

    void positionTaskInStack(TaskStack stack, int position, Rect bounds, Configuration config) {
        if (mStack != null && stack != mStack) {
            if (DEBUG_STACK) Slog.i(TAG, "positionTaskInStack: removing taskId=" + mTaskId
                    + " from stack=" + mStack);
            EventLog.writeEvent(EventLogTags.WM_TASK_REMOVED, mTaskId, "moveTask");
            mStack.removeTask(this);
        }
        stack.positionTask(this, position, showForAllUsers());
        setBounds(bounds, config);
    }

    boolean removeAppToken(AppWindowToken wtoken) {
        boolean removed = mAppTokens.remove(wtoken);
        if (mAppTokens.size() == 0) {
            EventLog.writeEvent(EventLogTags.WM_TASK_REMOVED, mTaskId, "removeAppToken: last token");
            if (mDeferRemoval) {
                removeLocked();
            }
        }
        wtoken.mTask = null;
        /* Leave mTaskId for now, it might be useful for debug
        wtoken.mTaskId = -1;
         */
        return removed;
    }

    void setSendingToBottom(boolean toBottom) {
        for (int appTokenNdx = 0; appTokenNdx < mAppTokens.size(); appTokenNdx++) {
            mAppTokens.get(appTokenNdx).sendingToBottom = toBottom;
        }
    }

    /** Set the task bounds. Passing in null sets the bounds to fullscreen. */
    int setBounds(Rect bounds, Configuration config) {
        if (config == null) {
            config = Configuration.EMPTY;
        }
        if (bounds == null && !Configuration.EMPTY.equals(config)) {
            throw new IllegalArgumentException("null bounds but non empty configuration: "
                    + config);
        }
        if (bounds != null && Configuration.EMPTY.equals(config)) {
            throw new IllegalArgumentException("non null bounds, but empty configuration");
        }
        boolean oldFullscreen = mFullscreen;
        int rotation = Surface.ROTATION_0;
        final DisplayContent displayContent = mStack.getDisplayContent();
        if (displayContent != null) {
            displayContent.getLogicalDisplayRect(mTmpRect);
            rotation = displayContent.getDisplayInfo().rotation;
            if (bounds == null) {
                bounds = mTmpRect;
                mFullscreen = true;
            } else {
                mFullscreen = mTmpRect.equals(bounds);
            }
        }

        if (bounds == null) {
            // Can't set to fullscreen if we don't have a display to get bounds from...
            return BOUNDS_CHANGE_NONE;
        }
        if (mPreScrollBounds.equals(bounds) && oldFullscreen == mFullscreen && mRotation == rotation) {
            return BOUNDS_CHANGE_NONE;
        }

        int boundsChange = BOUNDS_CHANGE_NONE;
        if (mPreScrollBounds.left != bounds.left || mPreScrollBounds.top != bounds.top) {
            boundsChange |= BOUNDS_CHANGE_POSITION;
        }
        if (mPreScrollBounds.width() != bounds.width() || mPreScrollBounds.height() != bounds.height()) {
            boundsChange |= BOUNDS_CHANGE_SIZE;
        }


        mPreScrollBounds.set(bounds);

        resetScrollLocked();

        mRotation = rotation;
        if (displayContent != null) {
            displayContent.mDimLayerController.updateDimLayer(this);
        }
        mOverrideConfig = mFullscreen ? Configuration.EMPTY : config;
        return boundsChange;
    }

    /**
     * Sets the bounds used to calculate the insets. See
     * {@link android.app.IActivityManager#resizeDockedStack} why this is needed.
     */
    void setTempInsetBounds(Rect tempInsetBounds) {
        if (tempInsetBounds != null) {
            mTempInsetBounds.set(tempInsetBounds);
        } else {
            mTempInsetBounds.setEmpty();
        }
    }

    /**
     * Gets the bounds used to calculate the insets. See
     * {@link android.app.IActivityManager#resizeDockedStack} why this is needed.
     */
    void getTempInsetBounds(Rect out) {
        out.set(mTempInsetBounds);
    }

    void setResizeable(int resizeMode) {
        mResizeMode = resizeMode;
    }

    boolean isResizeable() {
        return !mHomeTask
                && (ActivityInfo.isResizeableMode(mResizeMode) || mService.mForceResizableTasks);
    }

    boolean cropWindowsToStackBounds() {
        return !mHomeTask && (isResizeable() || mResizeMode == RESIZE_MODE_CROP_WINDOWS);
    }

    private boolean inCropWindowsResizeMode() {
        return !mHomeTask && !isResizeable() && mResizeMode == RESIZE_MODE_CROP_WINDOWS;
    }

    boolean resizeLocked(Rect bounds, Configuration configuration, boolean forced) {
        int boundsChanged = setBounds(bounds, configuration);
        if (forced) {
            boundsChanged |= BOUNDS_CHANGE_SIZE;
        }
        if (boundsChanged == BOUNDS_CHANGE_NONE) {
            return false;
        }
        if ((boundsChanged & BOUNDS_CHANGE_SIZE) == BOUNDS_CHANGE_SIZE) {
            resizeWindows();
        } else {
            moveWindows();
        }
        return true;
    }

    /**
     * Prepares the task bounds to be frozen with the current size. See
     * {@link AppWindowToken#freezeBounds}.
     */
    void prepareFreezingBounds() {
        mPreparedFrozenBounds.set(mBounds);
    }

    void resetScrollLocked() {
        if (mScrollValid) {
            mScrollValid = false;
            applyScrollToAllWindows(0, 0);
        }
        mBounds.set(mPreScrollBounds);
    }

    void applyScrollToAllWindows(final int xOffset, final int yOffset) {
        for (int activityNdx = mAppTokens.size() - 1; activityNdx >= 0; --activityNdx) {
            final ArrayList<WindowState> windows = mAppTokens.get(activityNdx).allAppWindows;
            for (int winNdx = windows.size() - 1; winNdx >= 0; --winNdx) {
                final WindowState win = windows.get(winNdx);
                win.mXOffset = xOffset;
                win.mYOffset = yOffset;
            }
        }
    }

    void applyScrollToWindowIfNeeded(final WindowState win) {
        if (mScrollValid) {
            win.mXOffset = mBounds.left;
            win.mYOffset = mBounds.top;
        }
    }

    boolean scrollLocked(Rect bounds) {
        // shift the task bound if it doesn't fully cover the stack area
        mStack.getDimBounds(mTmpRect);
        if (mService.mCurConfiguration.orientation == ORIENTATION_LANDSCAPE) {
            if (bounds.left > mTmpRect.left) {
                bounds.left = mTmpRect.left;
                bounds.right = mTmpRect.left + mBounds.width();
            } else if (bounds.right < mTmpRect.right) {
                bounds.left = mTmpRect.right - mBounds.width();
                bounds.right = mTmpRect.right;
            }
        } else {
            if (bounds.top > mTmpRect.top) {
                bounds.top = mTmpRect.top;
                bounds.bottom = mTmpRect.top + mBounds.height();
            } else if (bounds.bottom < mTmpRect.bottom) {
                bounds.top = mTmpRect.bottom - mBounds.height();
                bounds.bottom = mTmpRect.bottom;
            }
        }

        // We can stop here if we're already scrolling and the scrolled bounds not changed.
        if (mScrollValid && bounds.equals(mBounds)) {
            return false;
        }

        // Normal setBounds() does not allow non-null bounds for fullscreen apps.
        // We only change bounds for the scrolling case without change it size,
        // on resizing path we should still want the validation.
        mBounds.set(bounds);
        mScrollValid = true;
        applyScrollToAllWindows(bounds.left, bounds.top);
        return true;
    }

    /** Return true if the current bound can get outputted to the rest of the system as-is. */
    private boolean useCurrentBounds() {
        final DisplayContent displayContent = mStack.getDisplayContent();
        if (mFullscreen
                || !StackId.isTaskResizeableByDockedStack(mStack.mStackId)
                || displayContent == null
                || displayContent.getDockedStackLocked() != null) {
            return true;
        }
        return false;
    }

    /** Original bounds of the task if applicable, otherwise fullscreen rect. */
    void getBounds(Rect out) {
        if (useCurrentBounds()) {
            // No need to adjust the output bounds if fullscreen or the docked stack is visible
            // since it is already what we want to represent to the rest of the system.
            out.set(mBounds);
            return;
        }

        // The bounds has been adjusted to accommodate for a docked stack, but the docked stack is
        // not currently visible. Go ahead a represent it as fullscreen to the rest of the system.
        mStack.getDisplayContent().getLogicalDisplayRect(out);
    }

    /**
     * Calculate the maximum visible area of this task. If the task has only one app,
     * the result will be visible frame of that app. If the task has more than one apps,
     * we search from top down if the next app got different visible area.
     *
     * This effort is to handle the case where some task (eg. GMail composer) might pop up
     * a dialog that's different in size from the activity below, in which case we should
     * be dimming the entire task area behind the dialog.
     *
     * @param out Rect containing the max visible bounds.
     * @return true if the task has some visible app windows; false otherwise.
     */
    boolean getMaxVisibleBounds(Rect out) {
        boolean foundTop = false;
        for (int i = mAppTokens.size() - 1; i >= 0; i--) {
            final AppWindowToken token = mAppTokens.get(i);
            // skip hidden (or about to hide) apps
            if (token.mIsExiting || token.clientHidden || token.hiddenRequested) {
                continue;
            }
            final WindowState win = token.findMainWindow();
            if (win == null) {
                continue;
            }
            if (!foundTop) {
                out.set(win.mVisibleFrame);
                foundTop = true;
                continue;
            }
            if (win.mVisibleFrame.left < out.left) {
                out.left = win.mVisibleFrame.left;
            }
            if (win.mVisibleFrame.top < out.top) {
                out.top = win.mVisibleFrame.top;
            }
            if (win.mVisibleFrame.right > out.right) {
                out.right = win.mVisibleFrame.right;
            }
            if (win.mVisibleFrame.bottom > out.bottom) {
                out.bottom = win.mVisibleFrame.bottom;
            }
        }
        return foundTop;
    }

    /** Bounds of the task to be used for dimming, as well as touch related tests. */
    @Override
    public void getDimBounds(Rect out) {
        if (useCurrentBounds()) {
            if (inFreeformWorkspace() && getMaxVisibleBounds(out)) {
                return;
            }

            out.set(mBounds);
            return;
        }

        // The bounds has been adjusted to accommodate for a docked stack, but the docked stack
        // is not currently visible. Go ahead a represent it as fullscreen to the rest of the
        // system.
        mStack.getDisplayContent().getLogicalDisplayRect(out);
    }

    void setDragResizing(boolean dragResizing) {
        mDragResizing = dragResizing;
    }

    boolean isDragResizing() {
        return mDragResizing;
    }

    void updateDisplayInfo(final DisplayContent displayContent) {
        if (displayContent == null) {
            return;
        }
        if (mFullscreen) {
            setBounds(null, Configuration.EMPTY);
            return;
        }
        final int newRotation = displayContent.getDisplayInfo().rotation;
        if (mRotation == newRotation) {
            return;
        }

        // Device rotation changed.
        // - Reset the bounds to the pre-scroll bounds as whatever scrolling was done is no longer
        // valid.
        // - Rotate the bounds and notify activity manager if the task can be resized independently
        // from its stack. The stack will take care of task rotation for the other case.
        mTmpRect2.set(mPreScrollBounds);

        if (!StackId.isTaskResizeAllowed(mStack.mStackId)) {
            setBounds(mTmpRect2, mOverrideConfig);
            return;
        }

        displayContent.rotateBounds(mRotation, newRotation, mTmpRect2);
        if (setBounds(mTmpRect2, mOverrideConfig) != BOUNDS_CHANGE_NONE) {
            // Post message to inform activity manager of the bounds change simulating a one-way
            // call. We do this to prevent a deadlock between window manager lock and activity
            // manager lock been held.
            mService.mH.obtainMessage(RESIZE_TASK, mTaskId,
                    RESIZE_MODE_SYSTEM_SCREEN_ROTATION, mPreScrollBounds).sendToTarget();
        }
    }

    void resizeWindows() {
        final ArrayList<WindowState> resizingWindows = mService.mResizingWindows;
        for (int activityNdx = mAppTokens.size() - 1; activityNdx >= 0; --activityNdx) {
            final ArrayList<WindowState> windows = mAppTokens.get(activityNdx).allAppWindows;
            for (int winNdx = windows.size() - 1; winNdx >= 0; --winNdx) {
                final WindowState win = windows.get(winNdx);
                if (!resizingWindows.contains(win)) {
                    if (DEBUG_RESIZE) Slog.d(TAG, "resizeWindows: Resizing " + win);
                    resizingWindows.add(win);
                }
            }
        }
    }

    void moveWindows() {
        for (int activityNdx = mAppTokens.size() - 1; activityNdx >= 0; --activityNdx) {
            final ArrayList<WindowState> windows = mAppTokens.get(activityNdx).allAppWindows;
            for (int winNdx = windows.size() - 1; winNdx >= 0; --winNdx) {
                final WindowState win = windows.get(winNdx);
                if (DEBUG_RESIZE) Slog.d(TAG, "moveWindows: Moving " + win);
                win.mMovedByResize = true;
            }
        }
    }

    /**
     * Cancels any running app transitions associated with the task.
     */
    void cancelTaskWindowTransition() {
        for (int activityNdx = mAppTokens.size() - 1; activityNdx >= 0; --activityNdx) {
            mAppTokens.get(activityNdx).mAppAnimator.clearAnimation();
        }
    }

    /**
     * Cancels any running thumbnail transitions associated with the task.
     */
    void cancelTaskThumbnailTransition() {
        for (int activityNdx = mAppTokens.size() - 1; activityNdx >= 0; --activityNdx) {
            mAppTokens.get(activityNdx).mAppAnimator.clearThumbnail();
        }
    }

    boolean showForAllUsers() {
        final int tokensCount = mAppTokens.size();
        return (tokensCount != 0) && mAppTokens.get(tokensCount - 1).showForAllUsers;
    }

    boolean inHomeStack() {
        return mStack != null && mStack.mStackId == HOME_STACK_ID;
    }

    boolean inFreeformWorkspace() {
        return mStack != null && mStack.mStackId == FREEFORM_WORKSPACE_STACK_ID;
    }

    boolean inDockedWorkspace() {
        return mStack != null && mStack.mStackId == DOCKED_STACK_ID;
    }

    boolean isResizeableByDockedStack() {
        final DisplayContent displayContent = getDisplayContent();
        return displayContent != null && displayContent.getDockedStackLocked() != null
                && mStack != null && StackId.isTaskResizeableByDockedStack(mStack.mStackId);
    }

    /**
     * Whether the task should be treated as if it's docked. Returns true if the task
     * is currently in docked workspace, or it's side-by-side to a docked task.
     */
    boolean isDockedInEffect() {
        return inDockedWorkspace() || isResizeableByDockedStack();
    }

    boolean isTwoFingerScrollMode() {
        return inCropWindowsResizeMode() && isDockedInEffect();
    }

    WindowState getTopVisibleAppMainWindow() {
        final AppWindowToken token = getTopVisibleAppToken();
        return token != null ? token.findMainWindow() : null;
    }

    AppWindowToken getTopVisibleAppToken() {
        for (int i = mAppTokens.size() - 1; i >= 0; i--) {
            final AppWindowToken token = mAppTokens.get(i);
            // skip hidden (or about to hide) apps
            if (!token.mIsExiting && !token.clientHidden && !token.hiddenRequested) {
                return token;
            }
        }
        return null;
    }

    @Override
    public boolean isFullscreen() {
        if (useCurrentBounds()) {
            return mFullscreen;
        }
        // The bounds has been adjusted to accommodate for a docked stack, but the docked stack
        // is not currently visible. Go ahead a represent it as fullscreen to the rest of the
        // system.
        return true;
    }

    @Override
    public DisplayInfo getDisplayInfo() {
        return mStack.getDisplayContent().getDisplayInfo();
    }

    @Override
    public String toString() {
        return "{taskId=" + mTaskId + " appTokens=" + mAppTokens + " mdr=" + mDeferRemoval + "}";
    }

    @Override
    public String toShortString() {
        return "Task=" + mTaskId;
    }

    public void dump(String prefix, PrintWriter pw) {
        final String doublePrefix = prefix + "  ";

        pw.println(prefix + "taskId=" + mTaskId);
        pw.println(doublePrefix + "mFullscreen=" + mFullscreen);
        pw.println(doublePrefix + "mBounds=" + mBounds.toShortString());
        pw.println(doublePrefix + "mdr=" + mDeferRemoval);
        pw.println(doublePrefix + "appTokens=" + mAppTokens);
        pw.println(doublePrefix + "mTempInsetBounds=" + mTempInsetBounds.toShortString());

        final String triplePrefix = doublePrefix + "  ";

        for (int i = mAppTokens.size() - 1; i >= 0; i--) {
            final AppWindowToken wtoken = mAppTokens.get(i);
            pw.println(triplePrefix + "Activity #" + i + " " + wtoken);
            wtoken.dump(pw, triplePrefix);
        }

    }
}
