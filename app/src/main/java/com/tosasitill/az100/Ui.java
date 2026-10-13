package com.tosasitill.az100;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The module draws its own UI in two places -- the LSPosed entry activity and
 * the AZ100 page inside Melody's detail activity -- and they have to look like
 * one product.  Everything visual lives here so a change lands in both.
 *
 * <p>Built by hand rather than from a resource layout: the module's only
 * compile-time dependency is the Xposed API, so there is no appcompat or
 * material theme to inherit from, and pulling in a theme engine to draw four
 * switches is not worth the install size.  The pieces here are the few things
 * the stock widgets do badly at this size -- a card, a switch with a label on
 * the left, a meter -- and nothing more.
 *
 * <p>Deliberately flat: no elevation, no shadows.  Cards are told apart by a
 * one-step color difference from the page background, which survives both
 * themes and cannot clip inside a {@link ScrollView} on a ROM that shadows
 * badly.
 */
final class Ui {

    private Ui() {
    }

    /** Colors for one page, resolved once from the current night mode. */
    static final class Palette {
        final int bg;
        final int card;
        final int fg;
        final int fgSecondary;
        final int fgTertiary;
        final int accent;
        final int accentSoft;
        final int divider;
        final int trackOff;
        final int good;

        private Palette(boolean night) {
            if (night) {
                bg = Color.rgb(14, 15, 18);
                card = Color.rgb(26, 28, 32);
                fg = Color.rgb(242, 243, 245);
                fgSecondary = Color.rgb(162, 167, 176);
                fgTertiary = Color.rgb(113, 118, 127);
                accent = Color.rgb(107, 155, 255);
                accentSoft = Color.rgb(29, 40, 56);
                divider = Color.rgb(38, 40, 45);
                trackOff = Color.rgb(58, 62, 69);
                good = Color.rgb(61, 214, 140);
            } else {
                bg = Color.rgb(244, 245, 247);
                card = Color.WHITE;
                fg = Color.rgb(22, 24, 29);
                fgSecondary = Color.rgb(95, 100, 112);
                fgTertiary = Color.rgb(139, 144, 153);
                accent = Color.rgb(47, 111, 237);
                accentSoft = Color.rgb(232, 239, 254);
                divider = Color.rgb(236, 238, 242);
                trackOff = Color.rgb(211, 215, 222);
                good = Color.rgb(23, 166, 115);
            }
        }

        static Palette of(Context context) {
            boolean night = (context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            return new Palette(night);
        }
    }

    /** A scrollable page and the column to add content to. */
    static final class Page {
        final ScrollView scroll;
        final LinearLayout column;

        private Page(ScrollView scroll, LinearLayout column) {
            this.scroll = scroll;
            this.column = column;
        }
    }

    static Page page(Context context, Palette palette) {
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(palette.bg);
        // targetSdk 35 puts the window edge-to-edge on Android 15+, so without
        // this the first line of the page sits behind the action bar.  Inset
        // rather than hard-coded padding: the bar's height is the ROM's
        // business, and Melody's container has already consumed these insets,
        // so this is a no-op on the detail page.
        scroll.setFitsSystemWindows(true);
        // Stop the first focusable child (a switch) from taking focus on open,
        // which scrolls the page past its own header.
        scroll.setFocusable(false);
        scroll.setFocusableInTouchMode(false);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(context, 18), dp(context, 26), dp(context, 18), dp(context, 34));
        scroll.addView(column, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return new Page(scroll, column);
    }

    /* ---------------------------------------------------------------- text */

    /** The product name, once per page. */
    static TextView display(Context context, Palette palette, String value) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(26f);
        view.setTextColor(palette.fg);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    static TextView body(Context context, Palette palette, String value) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(15f);
        view.setTextColor(palette.fg);
        return view;
    }

    /** Secondary line under a title.  Also used for the config path note. */
    static TextView caption(Context context, Palette palette, String value) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(13f);
        view.setTextColor(palette.fgSecondary);
        view.setLineSpacing(dp(context, 3), 1f);
        return view;
    }

    /** Small all-caps group label above a card. */
    static TextView sectionLabel(Context context, Palette palette, String value) {
        TextView view = new TextView(context);
        view.setText(value.toUpperCase());
        view.setTextSize(11f);
        view.setTextColor(palette.fgTertiary);
        view.setLetterSpacing(0.14f);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    /** A status chip.  Re-colorable through {@link #setPill}. */
    static TextView pill(Context context, Palette palette, String value) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(12f);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setPadding(dp(context, 11), dp(context, 5), dp(context, 11), dp(context, 5));
        view.setBackground(rounded(context, palette.accentSoft, 100));
        view.setTextColor(palette.accent);
        return view;
    }

    static void setPill(TextView pill, Palette palette, boolean on, String value) {
        pill.setText(value);
        pill.setTextColor(on ? palette.good : palette.fgSecondary);
        pill.setBackground(rounded(context(pill), on ? soft(palette.good) : palette.accentSoft, 100));
    }

    /* --------------------------------------------------------------- cards */

    static LinearLayout card(Context context, Palette palette) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(context, palette.card, 18));
        card.setClipToPadding(false);
        return card;
    }

    /** One settings row: label and summary on the left, switch on the right. */
    static LinearLayout row(Context context, Palette palette, String label, String summary,
                            final Toggle toggle) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setText(label);
        title.setTextSize(15f);
        title.setTextColor(palette.fg);
        texts.addView(title);

        if (summary != null && !summary.isEmpty()) {
            TextView hint = caption(context, palette, summary);
            hint.setPadding(0, dp(context, 3), 0, 0);
            texts.addView(hint);
        }

        row.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toggleParams.leftMargin = dp(context, 16);
        row.addView(toggle, toggleParams);

        // The whole row is a touch target, not just the switch.
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                toggle.toggle();
            }
        });
        row.setClickable(true);
        return row;
    }

    static View divider(Context context, Palette palette) {
        View view = new View(context);
        view.setBackgroundColor(palette.divider);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(context, 1)));
        params.leftMargin = dp(context, 16);
        view.setLayoutParams(params);
        return view;
    }

    /** Body text inside a card, with the card's own horizontal padding. */
    static TextView paragraph(Context context, Palette palette, String value, int topPadding) {
        TextView view = caption(context, palette, value);
        view.setPadding(dp(context, 16), dp(context, topPadding), dp(context, 16), 0);
        return view;
    }

    /** A filled accent button. */
    static TextView button(Context context, Palette palette, String value) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(15f);
        view.setTextColor(palette.accent);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 18), dp(context, 12), dp(context, 18), dp(context, 12));
        view.setBackground(rounded(context, palette.accentSoft, 14));
        view.setClickable(true);
        view.setFocusable(false);
        return view;
    }

    static void setButtonEnabled(TextView button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.45f);
    }

    /* -------------------------------------------------------------- switch */

    interface OnChange {
        void onChanged(boolean checked);
    }

    /**
     * A switch with no text of its own.
     *
     * <p>The stock {@code Switch} puts its label to the left of the track,
     * which forces the label and the track onto one baseline and leaves no room
     * for a summary line.  This draws only the track, so the row can lay out
     * the text properly and the track stays put no matter how long the label
     * is.
     */
    static final class Toggle extends View {
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int offColor;
        private final int onColor;
        private final int thumbColor;
        private final float trackW;
        private final float trackH;
        private final float inset;

        private float progress;
        private boolean checked;
        private ValueAnimator animator;
        private OnChange listener;

        Toggle(Context context, Palette palette) {
            super(context);
            offColor = palette.trackOff;
            onColor = palette.accent;
            thumbColor = palette.card;
            trackW = dp(context, 48);
            trackH = dp(context, 29);
            inset = dp(context, 3);
            setClickable(true);
            setFocusable(false);
            setFocusableInTouchMode(false);
        }

        void setOnChange(OnChange listener) {
            this.listener = listener;
        }

        boolean isChecked() {
            return checked;
        }

        /** Sets the state without notifying the listener. */
        void setChecked(boolean value) {
            setChecked(value, false);
        }

        private void setChecked(boolean value, boolean notify) {
            if (checked == value && progress == (value ? 1f : 0f)) return;
            checked = value;
            if (animator != null) animator.cancel();
            animator = ValueAnimator.ofFloat(progress, value ? 1f : 0f);
            animator.setDuration(160);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator animation) {
                    progress = (Float) animation.getAnimatedValue();
                    invalidate();
                }
            });
            animator.start();
            if (notify && listener != null) listener.onChanged(value);
        }

        void toggle() {
            setChecked(!checked, true);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(resolveSize((int) trackW, widthSpec),
                    resolveSize((int) trackH, heightSpec));
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float radius = h / 2f;

            trackPaint.setColor(blend(offColor, onColor, progress));
            canvas.drawRoundRect(0, 0, w, h, radius, radius, trackPaint);

            float thumbRadius = radius - inset;
            float center = radius + (w - radius * 2f) * progress;
            thumbPaint.setColor(thumbColor);
            canvas.drawCircle(center, h / 2f, thumbRadius, thumbPaint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    setAlpha(0.7f);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    setAlpha(1f);
                    return true;
                case MotionEvent.ACTION_UP:
                    setAlpha(1f);
                    if (isEnabled()) toggle();
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }
    }

    /* --------------------------------------------------------------- meter */

    /** A thin rounded progress bar, for battery levels. */
    static final class Meter extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int trackColor;
        private final int fillColor;
        private final float thickness;
        private float fraction;

        Meter(Context context, Palette palette) {
            super(context);
            trackColor = palette.divider;
            fillColor = palette.accent;
            thickness = dp(context, 5);
        }

        void setFraction(float value) {
            fraction = Math.max(0f, Math.min(1f, value));
            invalidate();
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(resolveSize(dp(getContext(), 120), widthSpec),
                    resolveSize((int) thickness, heightSpec));
        }

        @Override protected void onDraw(Canvas canvas) {
            float h = getHeight();
            float radius = h / 2f;
            paint.setColor(trackColor);
            canvas.drawRoundRect(0, 0, getWidth(), h, radius, radius, paint);
            if (fraction <= 0f) return;
            paint.setColor(fillColor);
            canvas.drawRoundRect(0, 0, Math.max(h, getWidth() * fraction), h, radius, radius, paint);
        }
    }

    /* -------------------------------------------------------------- shared */

    static GradientDrawable rounded(Context context, int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    /** A same-hue background for a foreground color, for status chips. */
    private static int soft(int color) {
        return Color.argb(38, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int blend(int from, int to, float t) {
        return Color.argb(
                Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t),
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t));
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static Context context(View view) {
        return view.getContext();
    }

    /** The page shown when the config file cannot be opened at all. */
    static ScrollView errorScreen(Context context, Palette palette,
                                  String title, String detail, Throwable cause) {
        Page page = page(context, palette);
        page.column.addView(display(context, palette, title));
        TextView text = caption(context, palette, detail + "\n\n" + cause);
        text.setPadding(0, dp(context, 12), 0, 0);
        page.column.addView(text);
        return page.scroll;
    }
}
