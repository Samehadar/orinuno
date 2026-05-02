package com.orinuno.service.discovery.shikimori;

/**
 * One player URL discovered for a Shikimori anime. {@link #provider()} matches the {@code
 * episode_source.provider} discriminator (KODIK / ANIBOOM / SIBNET / JUTSU); {@link #url()} is the
 * raw URL Shikimori reported, untouched.
 */
public record ShikimoriPlayerLink(String provider, String url) {}
