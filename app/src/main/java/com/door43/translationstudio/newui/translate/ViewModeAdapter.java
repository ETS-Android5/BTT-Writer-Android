package com.door43.translationstudio.newui.translate;

import android.os.Bundle;
import android.support.v7.widget.RecyclerView;
import android.view.ViewGroup;

import com.door43.translationstudio.core.TranslationFormat;
import com.door43.translationstudio.core.TranslationViewMode;
import com.door43.translationstudio.core.TranslationWord;
import com.door43.translationstudio.core.TranslationNote;
import com.door43.translationstudio.rendering.ClickableRenderingEngine;
import com.door43.translationstudio.rendering.ClickableRenderingEngineFactory;
import com.door43.translationstudio.rendering.RenderingGroup;
import com.door43.translationstudio.spannables.Span;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by joel on 9/18/2015.
 */
public abstract class ViewModeAdapter<VH extends RecyclerView.ViewHolder> extends RecyclerView.Adapter<VH> {
    private List<VH> mViewHolders = new ArrayList<>();
    private OnEventListener mListener;
    private int mStartPosition = 0;

    /**
     * Returns the viewholder generated by the child class so we can keep track of it
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
     * Registeres the click listener
     * @param listener
     */
    public void setOnClickListener(OnEventListener listener) {
        mListener = listener;
    }

    /**
     * setup rendering group for translation format
     * @param format
     * @param renderingGroup
     * @param verseClickListener
     * @param noteClickListener
     * @param target - true if rendering target translations, false if source text
     * @return
     */
    public ClickableRenderingEngine setupRenderingGroup(TranslationFormat format, RenderingGroup renderingGroup, Span.OnClickListener verseClickListener, Span.OnClickListener noteClickListener, boolean target) {

        TranslationFormat defaultFormat = target ? TranslationFormat.USFM : TranslationFormat.USX;
        ClickableRenderingEngine renderer = ClickableRenderingEngineFactory.create(format, defaultFormat, verseClickListener, noteClickListener);
        renderingGroup.addEngine(renderer);
        return renderer;
    }

    /**
     * Notifies the adapter that it should rebuild it's view holders
     */
    abstract void rebuild();

    /**
     * Updates the source translation to be displayed
     * @param sourceTranslationId
     */
    abstract void setSourceTranslation(String sourceTranslationId);

    /**
     * Called when coordinating operations need to be applied to all the view holders
     * @param holder
     */
    abstract void onCoordinate(VH holder);

    /**
     * Requests the layout manager to coordinate all visible children in the list
     */
    protected void coordinateViewHolders() {
        for(VH holder:mViewHolders) {
            onCoordinate(holder);
        }
    }

    /**
     * returns the frame at the given position
     * @param position
     * @return
     */
    public abstract String getFocusedFrameId(int position);

    /**
     * returns the frame at the given position
     * @param position
     * @return
     */
    public abstract String getFocusedChapterId(int position);

    /**
     * Returns the position of an item in the adapter.
     * @param chapterId
     * @param frameId
     * @return -1 if no item is found
     */
    public abstract int getItemPosition(String chapterId, String frameId);

    /**
     * Restarts the auto commit timer
     */
    public void restartAutoCommitTimer() {
        mListener.restartAutoCommitTimer();
    }

    /**
     * Notifies the adpater that it needs to reload all it's data.
     */
    public abstract void reload();

    public interface OnEventListener {
        void onSourceTranslationTabClick(String sourceTranslationId);
        void onNewSourceTranslationTabClick();
        void closeKeyboard();
        void openTranslationMode(TranslationViewMode mode, Bundle extras);
        void onTranslationWordClick(String translationWordId, int width);
        void onTranslationArticleClick(String volume, String manual, String slug, int width);
        void onTranslationNoteClick(String chapterId, String frameId, String translationNoteId, int width);
        void onCheckingQuestionClick(String chapterId, String frameId, String checkingQuestionId, int width);
        void scrollToFrame(String chapterSlug, String frameSlug);
        void restartAutoCommitTimer();
    }
}
