import React, { useEffect, useMemo, useState } from "react";
import type { CreatorDraftRecord } from "../../../utils/db.ts";
import type { CreatorDraftSongGroup } from "./drafts.ts";

interface CreatorDraftLibraryProps {
  groups: CreatorDraftSongGroup[];
  currentDraftId?: string;
  onOpen: (id: string) => void | Promise<void>;
  onRemove: (id: string) => void | Promise<void>;
  onClose: () => void;
}

function draftDate(record: CreatorDraftRecord): string {
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(record.updatedAt));
}

export default function CreatorDraftLibrary({
  groups,
  currentDraftId,
  onOpen,
  onRemove,
  onClose,
}: CreatorDraftLibraryProps) {
  const [selectedGroupId, setSelectedGroupId] = useState(groups[0]?.id ?? "");
  const selectedGroup = useMemo(
    () => groups.find((group) => group.id === selectedGroupId) ?? groups[0] ?? null,
    [groups, selectedGroupId]
  );

  useEffect(() => {
    if (!groups.some((group) => group.id === selectedGroupId)) {
      setSelectedGroupId(groups[0]?.id ?? "");
    }
  }, [groups, selectedGroupId]);

  return (
    <div
      className="il-creator-drafts-backdrop"
      role="presentation"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <section
        className="il-creator-drafts-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="il-creator-drafts-title"
        onKeyDown={(event) => {
          if (event.key !== "Escape") return;
          event.preventDefault();
          event.stopPropagation();
          onClose();
        }}
      >
        <header>
          <div>
            <span className="il-creator-inspector__eyebrow">Local workspace</span>
            <h2 id="il-creator-drafts-title">Draft library</h2>
          </div>
          <button type="button" aria-label="Close draft library" onClick={onClose}>
            ×
          </button>
        </header>

        {groups.length === 0 ? (
          <div className="il-creator-drafts-empty">
            <strong>No saved drafts yet</strong>
            <span>Use Save draft and this library will organize versions by song.</span>
          </div>
        ) : (
          <div className="il-creator-drafts-layout">
            <nav aria-label="Songs with saved lyric drafts">
              {groups.map((group) => (
                <button
                  type="button"
                  className={group.id === selectedGroup?.id ? "is-active" : ""}
                  aria-current={group.id === selectedGroup?.id ? "true" : undefined}
                  onClick={() => setSelectedGroupId(group.id)}
                  key={group.id}
                >
                  {group.coverUrl ? (
                    <img src={group.coverUrl} alt="" />
                  ) : (
                    <span className="il-creator-cover-placeholder" aria-hidden="true" />
                  )}
                  <span>
                    <strong>{group.name}</strong>
                    <small>{group.artists.join(", ") || group.album || "Unknown song"}</small>
                    <em>
                      {group.drafts.length} draft{group.drafts.length === 1 ? "" : "s"}
                    </em>
                  </span>
                </button>
              ))}
            </nav>

            <main>
              <header>
                <div>
                  <h3>{selectedGroup?.name}</h3>
                  <p>
                    {selectedGroup?.artists.join(", ") || "Unknown artist"}
                    {selectedGroup?.album ? ` · ${selectedGroup.album}` : ""}
                  </p>
                </div>
              </header>
              <div className="il-creator-drafts-list">
                {selectedGroup?.drafts.map((draft, index) => (
                  <article
                    className={draft.id === currentDraftId ? "is-current" : ""}
                    key={draft.id}
                  >
                    <div>
                      <span>Version {selectedGroup.drafts.length - index}</span>
                      <strong>{draft.name}</strong>
                      <time dateTime={new Date(draft.updatedAt).toISOString()}>
                        Saved {draftDate(draft)}
                      </time>
                    </div>
                    <div>
                      <button
                        type="button"
                        className="is-primary"
                        onClick={() => void onOpen(draft.id)}
                      >
                        {draft.id === currentDraftId ? "Reload" : "Open"}
                      </button>
                      <button
                        type="button"
                        className="is-danger"
                        onClick={() => {
                          if (window.confirm(`Delete the draft “${draft.name}”?`)) {
                            void onRemove(draft.id);
                          }
                        }}
                      >
                        Delete
                      </button>
                    </div>
                  </article>
                ))}
              </div>
            </main>
          </div>
        )}
      </section>
    </div>
  );
}
