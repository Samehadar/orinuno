package com.orinuno.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orinuno.catalog.api.CatalogIdentityRequest;
import com.orinuno.catalog.model.CatalogContent;
import com.orinuno.catalog.model.CatalogContentExternalId;
import com.orinuno.catalog.model.CatalogContentKind;
import com.orinuno.catalog.model.CatalogSourceType;
import com.orinuno.catalog.repository.CatalogContentExternalIdRepository;
import com.orinuno.catalog.repository.CatalogContentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit-level coverage for the resolver's algorithm. Mocks both repositories and asserts the
 * resolver:
 *
 * <ul>
 *   <li>walks the lookup priority order shikimori → mal → imdb → kinopoisk → mdl → tmdb;
 *   <li>falls back to {@code (sourceType, sourceId)} when no external-db id matches;
 *   <li>inserts a fresh canonical row when no anchor is found and seeds it with all known external
 *       ids;
 *   <li>backfills chrome (titleRu/titleEn/kind/year) only when the anchor has nulls (first writer
 *       wins);
 *   <li>promotes a previously-null identity column on the anchor when a new external-database
 *       binding arrives;
 *   <li>logs and leaves alone bindings that already point at a different canonical row (no
 *       auto-merge in P1b);
 *   <li>idempotently attaches bindings (existing same-content binding → no-op);
 *   <li>treats {@link CatalogIdentityRequest} validation errors (KODIK/JUTSU in {@code
 *       externalIds}, blank {@code sourceId}) as programming bugs.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CatalogIdentityResolverTest {

    @Mock private CatalogContentRepository contentRepository;
    @Mock private CatalogContentExternalIdRepository externalIdRepository;

    private CatalogIdentityResolver resolver;
    private final Clock fixedClock =
            Clock.fixed(Instant.parse("2026-05-08T03:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        resolver = new CatalogIdentityResolver(contentRepository, externalIdRepository, fixedClock);
    }

    @Test
    @DisplayName(
            "no anchor anywhere → INSERT fresh canonical row seeded with every external-db id;"
                    + " bindings inserted for source-context + each external-db id")
    void freshInsertWhenNoAnchorExists() {
        CatalogIdentityRequest request =
                CatalogIdentityRequest.builder(CatalogSourceType.JUTSU, "naruto")
                        .shikimoriId("1")
                        .malId("20")
                        .titleRu("Наруто")
                        .titleEn("Naruto")
                        .kind(CatalogContentKind.ANIME)
                        .year(2002)
                        .build();

        when(contentRepository.findByShikimoriId("1")).thenReturn(Optional.empty());
        when(contentRepository.findByMalId("20")).thenReturn(Optional.empty());
        lenient()
                .when(
                        externalIdRepository.findByExternalId(
                                eq(CatalogSourceType.JUTSU), eq("naruto")))
                .thenReturn(Optional.empty());
        // Other external-id reverse lookups happen during attachAllBindings — make them empty.
        lenient()
                .when(
                        externalIdRepository.findByExternalId(
                                eq(CatalogSourceType.SHIKIMORI), eq("1")))
                .thenReturn(Optional.empty());
        lenient()
                .when(externalIdRepository.findByExternalId(eq(CatalogSourceType.MAL), eq("20")))
                .thenReturn(Optional.empty());

        // Stub insert to populate the auto-generated id (mirrors useGeneratedKeys).
        AtomicLong generated = new AtomicLong(42L);
        doAnswerInsertContent(generated.get());
        // After resolve, findById is called once more to return the final row.
        when(contentRepository.findById(generated.get()))
                .thenAnswer(
                        inv -> {
                            CatalogContent c = capturedInserted();
                            return Optional.of(c);
                        });

        CatalogContent resolved = resolver.findOrCreateContent(request);

        assertThat(resolved.getId()).isEqualTo(42L);
        assertThat(resolved.getShikimoriId()).isEqualTo("1");
        assertThat(resolved.getMalId()).isEqualTo("20");
        assertThat(resolved.getKind()).isEqualTo(CatalogContentKind.ANIME);
        assertThat(resolved.getTitleRu()).isEqualTo("Наруто");

        // Three bindings: JUTSU(naruto) + SHIKIMORI(1) + MAL(20).
        ArgumentCaptor<CatalogContentExternalId> bindings =
                ArgumentCaptor.forClass(CatalogContentExternalId.class);
        verify(externalIdRepository, atLeastOnce()).insert(bindings.capture());
        assertThat(bindings.getAllValues())
                .extracting(CatalogContentExternalId::getSourceType)
                .containsExactlyInAnyOrder(
                        CatalogSourceType.JUTSU,
                        CatalogSourceType.SHIKIMORI,
                        CatalogSourceType.MAL);
        assertThat(bindings.getAllValues())
                .allSatisfy(b -> assertThat(b.getContentId()).isEqualTo(42L));
    }

    @Test
    @DisplayName(
            "anchor found by shikimori → returns existing row; chrome backfilled only on null"
                    + " columns; existing chrome never overwritten (first writer wins)")
    void anchorFoundByShikimoriBackfillsOnlyNulls() {
        CatalogContent existing =
                CatalogContent.builder()
                        .id(7L)
                        .titleRu("Атака титанов") // already set
                        .titleEn(null) // null → can be filled
                        .kind(CatalogContentKind.ANIME)
                        .year(null) // null → can be filled
                        .shikimoriId("16498")
                        .build();
        when(contentRepository.findByShikimoriId("16498")).thenReturn(Optional.of(existing));
        when(contentRepository.findById(7L)).thenReturn(Optional.of(existing));
        // Bindings checks return empty → all three bindings are inserted.
        when(externalIdRepository.findByExternalId(any(), anyString()))
                .thenReturn(Optional.empty());

        CatalogIdentityRequest request =
                CatalogIdentityRequest.builder(CatalogSourceType.KODIK, "kodik-raw-aot")
                        .shikimoriId("16498")
                        .titleRu("Different Russian Title") // must NOT overwrite "Атака титанов"
                        .titleEn("Attack on Titan") // fills the null
                        .kind(CatalogContentKind.ANIME) // same kind, no change semantically
                        .year(2013) // fills the null
                        .build();

        CatalogContent resolved = resolver.findOrCreateContent(request);

        assertThat(resolved.getId()).isEqualTo(7L);
        // No INSERT into catalog_content because the anchor existed.
        verify(contentRepository, never()).insert(any());

        ArgumentCaptor<CatalogContent> patch = ArgumentCaptor.forClass(CatalogContent.class);
        verify(contentRepository, atLeastOnce()).update(patch.capture());

        // First update is the chrome backfill; later updates may be identity-column promotion
        // (none in this case because shikimori_id is already set on the anchor).
        CatalogContent chromePatch = patch.getAllValues().get(0);
        assertThat(chromePatch.getId()).isEqualTo(7L);
        assertThat(chromePatch.getTitleRu())
                .as("titleRu was already 'Атака титанов' so the patch must be null")
                .isNull();
        assertThat(chromePatch.getTitleEn()).isEqualTo("Attack on Titan");
        assertThat(chromePatch.getYear()).isEqualTo(2013);
    }

    @Test
    @DisplayName("anchor found via (sourceType, sourceId) fallback when no external-db ids match")
    void fallbackToSourceContextBinding() {
        CatalogContent existing =
                CatalogContent.builder()
                        .id(11L)
                        .titleEn("X")
                        .kind(CatalogContentKind.MOVIE)
                        .build();
        // Request has no external-db ids → priority loop does nothing.
        CatalogIdentityRequest request =
                CatalogIdentityRequest.builder(CatalogSourceType.JUTSU, "loner-anime").build();
        when(externalIdRepository.findByExternalId(CatalogSourceType.JUTSU, "loner-anime"))
                .thenReturn(
                        Optional.of(
                                CatalogContentExternalId.builder()
                                        .contentId(11L)
                                        .sourceType(CatalogSourceType.JUTSU)
                                        .externalId("loner-anime")
                                        .build()));
        when(contentRepository.findById(11L)).thenReturn(Optional.of(existing));

        CatalogContent resolved = resolver.findOrCreateContent(request);

        assertThat(resolved.getId()).isEqualTo(11L);
        verify(contentRepository, never()).insert(any());
        // No additional binding inserted — the (JUTSU, loner-anime) row was already there.
        verify(externalIdRepository, never()).insert(any());
    }

    @Test
    @DisplayName(
            "identity-column promotion: anchor resolved by shikimori but mal_id was null → new"
                    + " mal_id binding writes mal_id onto the anchor")
    void promotesIdentityColumnWhenNull() {
        CatalogContent anchor =
                CatalogContent.builder().id(33L).shikimoriId("9999").malId(null).build();
        when(contentRepository.findByShikimoriId("9999")).thenReturn(Optional.of(anchor));
        // findById is invoked twice — once during promoteIdentityColumnIfNull, once at end of
        // findOrCreateContent. Lenient stubbing tolerates the extra call.
        lenient().when(contentRepository.findById(33L)).thenReturn(Optional.of(anchor));
        when(externalIdRepository.findByExternalId(any(), anyString()))
                .thenReturn(Optional.empty());

        CatalogIdentityRequest request =
                CatalogIdentityRequest.builder(CatalogSourceType.JUTSU, "some-slug")
                        .shikimoriId("9999")
                        .malId("MAL-NEW") // anchor has null → must be promoted
                        .build();

        resolver.findOrCreateContent(request);

        // The promotion path issues an UPDATE with the anchor id and only mal_id populated.
        ArgumentCaptor<CatalogContent> patches = ArgumentCaptor.forClass(CatalogContent.class);
        verify(contentRepository, atLeastOnce()).update(patches.capture());
        boolean malPromotionApplied =
                patches.getAllValues().stream()
                        .anyMatch(
                                p ->
                                        Long.valueOf(33L).equals(p.getId())
                                                && "MAL-NEW".equals(p.getMalId())
                                                && p.getShikimoriId() == null);
        assertThat(malPromotionApplied)
                .as(
                        "expected at least one UPDATE that sets only mal_id on the anchor, but"
                                + " saw: %s",
                        patches.getAllValues())
                .isTrue();
    }

    @Test
    @DisplayName(
            "binding conflict (external id already attached to a different canonical row) is"
                    + " logged and left alone — no auto-merge in P1b")
    void bindingConflictIsLoggedNotMerged() {
        CatalogContent anchor = CatalogContent.builder().id(50L).shikimoriId("100").build();
        when(contentRepository.findByShikimoriId("100")).thenReturn(Optional.of(anchor));
        lenient().when(contentRepository.findById(50L)).thenReturn(Optional.of(anchor));

        // (SHIKIMORI, 100) attaches cleanly during anchor lookup → not relevant here.
        when(externalIdRepository.findByExternalId(CatalogSourceType.SHIKIMORI, "100"))
                .thenReturn(Optional.empty());
        // (JUTSU, target) is already pointing at a *different* canonical row 99.
        when(externalIdRepository.findByExternalId(CatalogSourceType.JUTSU, "target"))
                .thenReturn(
                        Optional.of(
                                CatalogContentExternalId.builder()
                                        .id(777L)
                                        .contentId(99L)
                                        .sourceType(CatalogSourceType.JUTSU)
                                        .externalId("target")
                                        .build()));

        CatalogIdentityRequest request =
                CatalogIdentityRequest.builder(CatalogSourceType.JUTSU, "target")
                        .shikimoriId("100")
                        .build();

        CatalogContent resolved = resolver.findOrCreateContent(request);

        assertThat(resolved.getId()).isEqualTo(50L);
        // The conflicting binding must NOT be re-pointed.
        verify(externalIdRepository, never()).reassignContent(any(), anyString(), anyLong());
        // No INSERT for the conflicting (JUTSU, target) binding either.
        ArgumentCaptor<CatalogContentExternalId> inserts =
                ArgumentCaptor.forClass(CatalogContentExternalId.class);
        verify(externalIdRepository, atLeastOnce()).insert(inserts.capture());
        assertThat(inserts.getAllValues())
                .extracting(b -> b.getSourceType() + ":" + b.getExternalId())
                .doesNotContain("JUTSU:target");
    }

    @Test
    @DisplayName(
            "attachExternalId: fresh binding is inserted; identity column promoted on the anchor"
                    + " when the column was null")
    void attachExternalIdFreshInsertAndPromote() {
        CatalogContent anchor = CatalogContent.builder().id(60L).build();
        when(externalIdRepository.findByExternalId(CatalogSourceType.IMDB, "tt9999"))
                .thenReturn(Optional.empty());
        lenient().when(contentRepository.findById(60L)).thenReturn(Optional.of(anchor));

        CatalogContentExternalId result =
                resolver.attachExternalId(60L, CatalogSourceType.IMDB, "tt9999");

        assertThat(result.getContentId()).isEqualTo(60L);
        assertThat(result.getSourceType()).isEqualTo(CatalogSourceType.IMDB);
        assertThat(result.getExternalId()).isEqualTo("tt9999");

        ArgumentCaptor<CatalogContent> patches = ArgumentCaptor.forClass(CatalogContent.class);
        verify(contentRepository).update(patches.capture());
        assertThat(patches.getValue().getImdbId()).isEqualTo("tt9999");
    }

    @Test
    @DisplayName(
            "attachExternalId: existing binding to the SAME content is returned as-is, no INSERT,"
                    + " no UPDATE")
    void attachExternalIdIdempotentSameContent() {
        CatalogContentExternalId existing =
                CatalogContentExternalId.builder()
                        .id(800L)
                        .contentId(60L)
                        .sourceType(CatalogSourceType.SHIKIMORI)
                        .externalId("12345")
                        .build();
        when(externalIdRepository.findByExternalId(CatalogSourceType.SHIKIMORI, "12345"))
                .thenReturn(Optional.of(existing));

        CatalogContentExternalId result =
                resolver.attachExternalId(60L, CatalogSourceType.SHIKIMORI, "12345");

        assertThat(result).isSameAs(existing);
        verify(externalIdRepository, never()).insert(any());
        verify(contentRepository, never()).update(any());
    }

    @Test
    @DisplayName(
            "attachExternalId: existing binding to a DIFFERENT content is returned untouched"
                    + " (logged but no merge)")
    void attachExternalIdConflictReturnsExisting() {
        CatalogContentExternalId existing =
                CatalogContentExternalId.builder()
                        .id(801L)
                        .contentId(99L)
                        .sourceType(CatalogSourceType.MAL)
                        .externalId("777")
                        .build();
        when(externalIdRepository.findByExternalId(CatalogSourceType.MAL, "777"))
                .thenReturn(Optional.of(existing));

        CatalogContentExternalId result =
                resolver.attachExternalId(60L, CatalogSourceType.MAL, "777");

        assertThat(result.getContentId()).isEqualTo(99L);
        verify(externalIdRepository, never()).insert(any());
        verify(externalIdRepository, never()).reassignContent(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("CatalogIdentityRequest validation: rejects KODIK / JUTSU in externalIds")
    void requestRejectsSourceContextInExternalIds() {
        assertThatThrownBy(
                        () ->
                                CatalogIdentityRequest.builder(CatalogSourceType.JUTSU, "x")
                                        .externalId(CatalogSourceType.KODIK, "kodik-id")
                                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("external databases");
    }

    @Test
    @DisplayName(
            "lookup priority: shikimori beats mal — when both ids match different canonical rows,"
                    + " shikimori winner is returned")
    void shikimoriBeatsMalInLookupPriority() {
        CatalogContent shikimoriOwner =
                CatalogContent.builder().id(101L).shikimoriId("1").malId("OTHER").build();
        CatalogContent malOwner =
                CatalogContent.builder().id(202L).shikimoriId(null).malId("2").build();
        when(contentRepository.findByShikimoriId("1")).thenReturn(Optional.of(shikimoriOwner));
        // findByMalId should not even be called.
        lenient().when(contentRepository.findByMalId("2")).thenReturn(Optional.of(malOwner));
        lenient().when(contentRepository.findById(101L)).thenReturn(Optional.of(shikimoriOwner));
        when(externalIdRepository.findByExternalId(any(), anyString()))
                .thenReturn(Optional.empty());

        CatalogIdentityRequest request =
                CatalogIdentityRequest.builder(CatalogSourceType.JUTSU, "ambiguous")
                        .shikimoriId("1")
                        .malId("2")
                        .build();

        CatalogContent resolved = resolver.findOrCreateContent(request);

        assertThat(resolved.getId()).isEqualTo(101L);
        verify(contentRepository).findByShikimoriId("1");
        verify(contentRepository, never()).findByMalId("2");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private CatalogContent lastInsertedContent;

    private void doAnswerInsertContent(long generatedId) {
        // Mockito.doAnswer is awkward without an explicit method ref because of generics; we use
        // when(...).thenAnswer with a captor that mutates the argument the way useGeneratedKeys
        // would.
        org.mockito.Mockito.doAnswer(
                        inv -> {
                            CatalogContent arg = inv.getArgument(0);
                            arg.setId(generatedId);
                            lastInsertedContent = arg;
                            return null;
                        })
                .when(contentRepository)
                .insert(any());
    }

    private CatalogContent capturedInserted() {
        return lastInsertedContent;
    }

    @SuppressWarnings("unused")
    private static ZoneId tz() {
        return ZoneId.of("UTC");
    }
}
