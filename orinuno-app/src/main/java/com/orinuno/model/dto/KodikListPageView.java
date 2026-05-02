package com.orinuno.model.dto;

import java.util.List;

/**
 * Page wrapper for Kodik /list proxy. Uses raw {@code next_page} URL from Kodik so callers can pass
 * it back unchanged via {@code ?nextPage=...}.
 */
public record KodikListPageView(
        List<KodikListItemView> results, Integer total, String nextPage, String prevPage) {}
