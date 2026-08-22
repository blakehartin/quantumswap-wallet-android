package com.quantumswap.app.view.widget;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.TextView;

import com.quantumswap.app.R;
import com.quantumswap.app.networking.UrlBuilder;

/** Renders "A / B" pair labels with each token symbol as a link to
 *  the token contract on the block explorer (desktop pool / position
 *  rows link the symbols the same way). */
public final class ExplorerLinks {

    private ExplorerLinks() { }

    public static void setPairLabel(TextView target, String symA, String tokenA,
                                    String symB, String tokenB) {
        Context ctx = target.getContext();
        int linkColor = ctx.getResources().getColor(R.color.quantumTeal);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        appendTokenLink(sb, ctx, symA, tokenA, linkColor);
        sb.append(" / ");
        appendTokenLink(sb, ctx, symB, tokenB, linkColor);
        target.setText(sb);
        target.setMovementMethod(LinkMovementMethod.getInstance());
        target.setHighlightColor(Color.TRANSPARENT);
    }

    /** Make a TextView value a block-explorer link (teal, underlined). */
    public static void linkValue(TextView target, String value, Uri url) {
        target.setText(value == null ? "" : value);
        if (url == null) return;
        Context ctx = target.getContext();
        SpannableStringBuilder sb = new SpannableStringBuilder(value);
        sb.setSpan(new ClickableSpan() {
            @Override public void onClick(View widget) {
                try { ctx.startActivity(new Intent(Intent.ACTION_VIEW, url)); } catch (Throwable ignore) { }
            }
        }, 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(ctx.getResources().getColor(R.color.quantumTeal)),
                0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.UnderlineSpan(), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        target.setText(sb);
        target.setMovementMethod(LinkMovementMethod.getInstance());
        target.setHighlightColor(Color.TRANSPARENT);
    }

    private static void appendTokenLink(SpannableStringBuilder sb, final Context ctx,
                                        String label, final String tokenAddress, int color) {
        int start = sb.length();
        sb.append(label);
        final Uri url = UrlBuilder.blockExplorerTokenUrl(tokenAddress);
        if (url == null) return;
        sb.setSpan(new ClickableSpan() {
            @Override public void onClick(View widget) {
                try { ctx.startActivity(new Intent(Intent.ACTION_VIEW, url)); } catch (Throwable ignore) { }
            }
        }, start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
