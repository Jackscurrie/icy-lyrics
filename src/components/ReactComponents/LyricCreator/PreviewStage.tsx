import React, { useEffect, useRef } from "react";
import PageView, { PageContainer } from "../../Pages/PageView.ts";
import ApplyLyrics from "../../../utils/Lyrics/Global/Applyer.ts";
import fetchLyrics, { invalidateLyricsRequests } from "../../../utils/Lyrics/fetchLyrics.ts";
import { SpotifyPlayer } from "../../Global/SpotifyPlayer.ts";
import { acquireLyricsClockOverride } from "../../../utils/Lyrics/lyrics.ts";
import { creatorProjectToLyrics, type CreatorProject } from "./model.ts";
import {
  acquireCreatorPreviewOwnership,
  constrainCreatorPreviewPage,
  CreatorPreviewRenderQueue,
  creatorPreviewHasRenderedLyrics,
  prepareCreatorPreviewSurface,
  resolveCreatorPreviewPage,
} from "./previewOwnership.ts";

interface PreviewStageProps {
  project: CreatorProject;
  clock: () => number;
}

export default function PreviewStage({ project, clock }: PreviewStageProps) {
  const hostRef = useRef<HTMLDivElement>(null);
  const pageRef = useRef<HTMLElement | null>(null);
  const clockRef = useRef(clock);
  const projectRef = useRef(project);
  const renderQueueRef = useRef<CreatorPreviewRenderQueue<CreatorProject> | null>(null);

  clockRef.current = clock;
  projectRef.current = project;

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;

    let cancelled = false;
    let createdPage = false;
    let page: HTMLElement | null = null;
    let originalParent: Node | null = null;
    let originalNextSibling: Node | null = null;
    let releasePreviewBounds: (() => void) | null = null;
    const releaseClock = acquireLyricsClockOverride(() => {
      const positionMs = clockRef.current();
      return { positionMs, rawPositionMs: positionMs };
    });
    const releasePreviewOwnership = acquireCreatorPreviewOwnership();

    invalidateLyricsRequests(SpotifyPlayer.GetUri() ?? null);

    const waitForPaint = () =>
      new Promise<void>((resolve) => {
        requestAnimationFrame(() => resolve());
      });

    const mountActualRenderer = async () => {
      const resolution = await resolveCreatorPreviewPage({
        getPage: () => PageContainer,
        isPageOpen: () => PageView.IsOpened,
        openPage: () => PageView.Open(host, { previewMode: true }),
      });
      if (!resolution) return;

      page = resolution.page;
      createdPage = resolution.createdPage;
      if (cancelled) {
        if (createdPage && PageContainer === page) await PageView.Destroy();
        return;
      }

      if (!createdPage) {
        originalParent = page.parentNode;
        originalNextSibling = page.nextSibling;
        host.appendChild(page);
      }

      if (!page || cancelled) return;
      page.classList.add("IcyLyricCreatorPreview");
      prepareCreatorPreviewSurface(page);
      releasePreviewBounds = constrainCreatorPreviewPage(page, host);
      pageRef.current = page;

      const queue = new CreatorPreviewRenderQueue<CreatorProject>({
        schedule: (callback) => requestAnimationFrame(callback),
        cancelSchedule: (handle) => cancelAnimationFrame(handle),
        maxAttempts: 8,
        render: async (nextProject) => {
          const activePage = pageRef.current;
          if (!activePage || PageContainer !== activePage || !host.contains(activePage)) {
            return false;
          }

          prepareCreatorPreviewSurface(activePage);
          await ApplyLyrics([creatorProjectToLyrics(nextProject), 200]);

          const expectsVisibleText = nextProject.lines.some((line) =>
            line.tokens.some((token) =>
              token.fragments.some((fragment) => fragment.text.trim().length > 0)
            )
          );
          // SimpleBar and the virtualizer install their first visible window
          // after layout. Give them several paints before deciding the render
          // was skipped and retrying the latest project.
          for (let paint = 0; paint < 4; paint += 1) {
            if (cancelled || pageRef.current !== activePage || PageContainer !== activePage) {
              return false;
            }
            if (creatorPreviewHasRenderedLyrics(activePage, expectsVisibleText)) return true;
            await waitForPaint();
          }
          return creatorPreviewHasRenderedLyrics(activePage, expectsVisibleText);
        },
      });
      renderQueueRef.current = queue;
      queue.update(projectRef.current);
    };

    void mountActualRenderer();

    return () => {
      cancelled = true;
      renderQueueRef.current?.dispose();
      renderQueueRef.current = null;
      releaseClock();
      releasePreviewOwnership();
      pageRef.current = null;
      releasePreviewBounds?.();
      releasePreviewBounds = null;
      if (page) page.classList.remove("IcyLyricCreatorPreview");

      if (createdPage) {
        if (PageContainer === page) void PageView.Destroy();
        return;
      }

      if (page && originalParent) {
        if (originalNextSibling?.parentNode === originalParent) {
          originalParent.insertBefore(page, originalNextSibling);
        } else {
          originalParent.appendChild(page);
        }
      }

      const uri = SpotifyPlayer.GetUri();
      if (uri) {
        invalidateLyricsRequests(uri);
        void fetchLyrics(uri).then(ApplyLyrics);
      }
    };
  }, []);

  useEffect(() => {
    renderQueueRef.current?.update(project);
  }, [project]);

  return (
    <section className="il-creator-preview" aria-label="Live lyric render preview">
      <div className="il-creator-preview__badge">LIVE RENDER</div>
      <div ref={hostRef} className="il-creator-preview__host" />
    </section>
  );
}
