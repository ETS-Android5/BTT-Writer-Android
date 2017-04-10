package com.door43.translationstudio.ui.translate;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SectionIndexer;
import android.widget.TextView;

import com.door43.translationstudio.core.SlugSorter;
import com.door43.translationstudio.core.TargetTranslation;
import com.door43.translationstudio.core.TranslationType;
import com.door43.translationstudio.core.TranslationViewMode;
import com.door43.translationstudio.core.Typography;
import com.door43.translationstudio.tasks.CheckForMergeConflictsTask;
import com.door43.translationstudio.ui.translate.review.SearchSubject;

import org.unfoldingword.door43client.models.Translation;
import org.unfoldingword.resourcecontainer.ResourceContainer;
import org.unfoldingword.tools.logger.Logger;
import org.unfoldingword.tools.taskmanager.ManagedTask;
import org.unfoldingword.tools.taskmanager.TaskManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by joel on 9/18/2015.
 */
public abstract class ViewModeAdapter<VH extends RecyclerView.ViewHolder> extends RecyclerView.Adapter<VH>  implements SectionIndexer, ManagedTask.OnFinishedListener {
    private List<VH> mViewHolders = new ArrayList<>();
    private OnEventListener mListener;
    private int mStartPosition = 0;
    protected String startingChapterSlug;
    protected String startingChunkSlug;
    private int currentPosition = -1;
    private MovementDirection currentMovementDirection = MovementDirection.UNKNOWN;
    protected boolean mShowMergeSummary = false;

    private enum MovementDirection {
        UP,
        DOWN,
        UNKNOWN
    }

    /**
     * Returns the ViewHolder generated by the child class so we can keep track of it
     * @param parent
     * @param viewType
     * @return
     */
    abstract VH onCreateManagedViewHolder(ViewGroup parent, int viewType);

    @Override
    public final VH onCreateViewHolder(ViewGroup parent, int viewType) {
        VH holder = onCreateManagedViewHolder(parent, viewType);
        mViewHolders.add(holder);
        return holder;
    }

    /**
     * Binds the ViewHolder to the current position
     * @param holder
     * @param position
     */
    abstract void onBindManagedViewHolder(VH holder, int position);

    @Override
    public final void onBindViewHolder(VH holder, int position) {
        onBindManagedViewHolder(holder, position);
        int[] range = calculateVisibleItems(position);
        onVisiblePositionsChanged(range);
    }

    /**
     * Calculates a theoretical range of visible positions.
     * You should validate the upper bound.
     *
     * @param nextPosition the next position visited in the list
     * @return the range of visible positions.
     */
    private int[] calculateVisibleItems(int nextPosition) {
        int max = 0;
        int min = 0;
        if(currentMovementDirection == MovementDirection.DOWN) {
            if(nextPosition >= this.currentPosition) {
                // continue direction
                max = nextPosition;
                min = nextPosition - (mViewHolders.size() - 1);
            } else {
                // reverse
                max = nextPosition + mViewHolders.size() - 1;
                min =  nextPosition;
                currentMovementDirection = MovementDirection.UP;
            }
        } else if(currentMovementDirection == MovementDirection.UP) {
            if(nextPosition <= this.currentPosition) {
                // continue direction
                max = nextPosition + mViewHolders.size() - 1;
                min = nextPosition;
            } else {
                // reverse
                max = nextPosition;
                min = nextPosition - (mViewHolders.size() - 1);
                currentMovementDirection = MovementDirection.DOWN;
            }
        } else {
            max = nextPosition + mViewHolders.size() - 1;
            min = nextPosition - (mViewHolders.size() - 1);
        }

        // constrain bounds
        if(min < 0) {
            max -= min; // TRICKY: -- = +
            min = 0;
        }

        return new int[]{min, max};
    }

    /**
     * Called when the range of visible positions has changed.
     * You should validate the upper bound.
     *
     * @param range the theoretical range of visible positions.
     */
    protected void onVisiblePositionsChanged(int[] range) {
        // stub
    }

    /**
     * Returns the start position where the list should start when first built
     * @return
     */
    protected int getListStartPosition() {
        return mStartPosition;
    }

    /**
     * Sets the position where the list should start when first built
     * @param startPosition
     */
    protected void setListStartPosition(int startPosition) {
        mStartPosition = startPosition;
    }

    /**
     * Returns the registered click listener
     * @return
     */
    protected OnEventListener getListener() {
        return mListener;
    }

    /**
     * Registers the click listener
     * @param listener
     */
    public void setOnClickListener(OnEventListener listener) {
        mListener = listener;
    }

    /**
     * Updates the source translation to be displayed.
     * This should call notifyDataSetChanged()
     * @param sourceContainer
     */
    abstract void setSourceContainer(ResourceContainer sourceContainer);

    /**
     * Called when coordinating operations need to be applied to all the view holders
     * @param holder
     */
    abstract void onCoordinate(VH holder);

    /**
     * set true if we want to initially show a summary of merge conflicts
     * @param showMergeSummary
     */
    public void setShowMergeSummary(boolean showMergeSummary) {
        mShowMergeSummary = showMergeSummary;
    }

    /**
     * get the chapter slug for the position
     * @param position
     */
    public String getChapterSlug(int position) {
        int section = getSectionForPosition( position);
        Object[] sections = getSections();
        if(section >= 0 && section < sections.length) {
            return (String) sections[section];
        }
        return "";
    }

    /**
     * Requests the layout manager to coordinate all visible children in the list
     */
    protected void coordinateViewHolders() {
        for(VH holder:mViewHolders) {
            onCoordinate(holder);
        }
    }

    /**
     * calls notify dataset changed and triggers some other actions
     */
    protected void triggerNotifyDataSetChanged() {
        notifyDataSetChanged();
        if(mListener != null) {
            mListener.onDataSetChanged(getItemCount());
        }
    }

    /**
     * Filters the adapter by the constraint
     * @param constraint the search query
     * @param subject the text that will be searched
     */
    public void filter(CharSequence constraint, SearchSubject subject, int initialPosition) {
        // Override this in your adapter to enable searching
    }

    /**
     * move to next/previous search item
     * @param next if true then find next, otherwise will find previous
     */
    public void onMoveSearch(boolean next) {
        // Override this in your adapter to enable next/previous
    }

    protected void initializeListItems(List<ListItem> items, List<String> chapters, ResourceContainer sourceContainer) {
        // TODO: there is also a map form of the toc.
        setListStartPosition(0);
        items.clear();
        chapters.clear();
        boolean foundStartPosition = false;
        if(sourceContainer != null) {
            SlugSorter sorter = new SlugSorter();
            List<String> chapterSlugs = sorter.sort(sourceContainer.chapters());

            for (String chapterSlug : chapterSlugs) {
                chapters.add(chapterSlug);
                List<String> chunkSlugs = sorter.sort(sourceContainer.chunks(chapterSlug));
                for (String chunkSlug : chunkSlugs) {
                    if (!foundStartPosition && chapterSlug.equals(startingChapterSlug) && (chunkSlug.equals(startingChunkSlug) || startingChunkSlug == null)) {
                        setListStartPosition(items.size());
                        foundStartPosition = true;
                    }
                    items.add(createListItem(chapterSlug, chunkSlug));
                }
            }
        }
    }

    /**
     * need to override
     * @param chapterSlug
     * @param chunkSlug
     * @return
     */
    public abstract ListItem createListItem(String chapterSlug, String chunkSlug);

    /**
     * check all cards for merge conflicts to see if we should show warning.  Runs as background task.
     */
    protected void doCheckForMergeConflictTask(List<ListItem> items, ResourceContainer sourceContainer, TargetTranslation targetTranslation) {
        if((items != null) && (items.size() > 0) ) {  // make sure initialized
            CheckForMergeConflictsTask task = new CheckForMergeConflictsTask(items, sourceContainer, targetTranslation);
            task.addOnFinishedListener(this);
            TaskManager.addTask(task, CheckForMergeConflictsTask.TASK_ID);
        }
    }

    @Override
    public abstract void onTaskFinished(ManagedTask task);

    /**
     * enable/disable merge conflict filter in adapter
     * @param enableFilter
     * @param forceMergeConflict - if true, then will initialize have merge conflict flag to true
     */
    public void setMergeConflictFilter(boolean enableFilter, boolean forceMergeConflict) {
        // Override this in your adapter to enable merge conflict filtering
    }

    /**
     * Checks if filtering is enabled for this adapter.
     * Override this to customize filtering.
     * @return
     */
    public boolean hasFilter() {
        return false;
    }

    /**
     * returns the frame at the given position
     * @param position
     * @return
     */
    public abstract String getFocusedChunkSlug(int position);

    /**
     * returns the frame at the given position
     * @param position
     * @return
     */
    public abstract String getFocusedChapterSlug(int position);

    /**
     * Returns the position of an item in the adapter.
     * @param chapterSlug
     * @param chunkSlug
     * @return -1 if no item is found
     */
    public abstract int getItemPosition(String chapterSlug, String chunkSlug);

    /**
     * Returns the corresponding chunk slug.
     * Override this method if you need to map verses to chunks.
     *
     * @param chapterSlug
     * @param verseSlug
     * @return
     */
    public String getVerseChunk(String chapterSlug, String verseSlug) {
        // stub
        return verseSlug;
    }

    /**
     * Restarts the auto commit timer
     */
    public void restartAutoCommitTimer() {
        mListener.restartAutoCommitTimer();
    }

    /**
     * if better font for language, save language info in values
     * @param context
     * @param st
     * @param values
     */
    public static void getFontForLanguageTab(Context context, Translation st, ContentValues values) {
        //see if there is a special font for tab
        Typeface typeface = Typography.getBestFontForLanguage(context, TranslationType.SOURCE, st.language.slug, st.language.direction);
        if(typeface != Typeface.DEFAULT) {
            values.put("language", st.language.slug);
            values.put("direction", st.language.direction);
        }
    }


    /**
     * if language is specified in values, finds the created tab that has the title text and applies the Typeface for the language
     * @param context
     * @param layout
     * @param values
     * @param title
     */
    public static void applyLanguageTypefaceToTab(Context context, ViewGroup layout, ContentValues values, String title) {
        if(values.containsKey("language")) {
            String code = values.getAsString("language");
            String direction = values.getAsString("direction");
            Typeface typeface = Typography.getBestFontForLanguage(context, TranslationType.SOURCE, code, direction);
            TextView view = ViewModeAdapter.findTab(layout, title);
            if(view != null) {
                view.setTypeface(typeface, 0);
            }
        }
    }

    /**
     * finds a TextView with match text within viewGroup (recursive)
     * @param viewGroup
     * @param match
     * @return
     */
    public static TextView findTab(ViewGroup viewGroup, String match) {

        int count = viewGroup.getChildCount();
        for (int i = 0; i < count; i++) {
            View view = viewGroup.getChildAt(i);
            if (view instanceof ViewGroup) {
                TextView foundView = findTab((ViewGroup) view, match);
                if(foundView != null) {
                    return foundView;
                }
            }
            else if (view instanceof TextView) {
                TextView textView = (TextView) view;
                CharSequence text = textView.getText();
                if(match.equals(text.toString())) {
                    return textView;
                }
            }
        }
        return null;
    }

    /**
     * called to set new selected position
     * @param position
     * @param offset - if greater than or equal to 0, then set specific offset
     */
    protected void onSetSelectedPosition(int position, int offset) {
        if(mListener != null) {
            mListener.onSetSelectedPosition(position, offset);
        }
    }

    public interface OnEventListener {
        void onSourceTranslationTabClick(String sourceTranslationId);
        void onNewSourceTranslationTabClick();
        void closeKeyboard();
        void openTranslationMode(TranslationViewMode mode, Bundle extras);
        void onTranslationWordClick(String resourceContainerSlug, String chapterSlug, int width);
        void onTranslationArticleClick(String volume, String manual, String slug, int width);
        void onTranslationNoteClick(TranslationHelp note, int width);
        void onTranslationQuestionClick(TranslationHelp question, int width);
        void scrollToChunk(String chapterSlug, String frameSlug);
        void restartAutoCommitTimer();
        void onSearching(boolean enable, int foundCount, boolean atEnd, boolean atStart);
        void onDataSetChanged(int count);
        void onEnableMergeConflict(boolean showConflicted, boolean active);
        void onSetSelectedPosition(int position, int offset);
        RecyclerView.ViewHolder getVisibleViewHolder(int position);
    }
}
