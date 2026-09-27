import { useStore } from "@nanostores/react";
import React, { useEffect, useRef, useState } from "react";
import { toast } from "sonner";
import { $creatorAutoTimingLastModel } from "../../../utils/stores.ts";
import {
  autoTimingModelRepository,
} from "../LyricCreator/autoTiming/modelRepository.ts";
import type {
  AutoTimingInstallProgress,
  AutoTimingModelId,
  AutoTimingModuleStatus,
} from "../LyricCreator/autoTiming/types.ts";
import { matches, Row, SectionTitle, Select } from "./components.tsx";
import { CreatorTaskScope } from "../LyricCreator/autoTiming/session.ts";

const SECTION_NAME = "Lyric Creator";

interface Props {
  query: string;
  sectionFilter: string;
}

function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return "Unknown size";
  const units = ["B", "KiB", "MiB", "GiB"];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(unit >= 2 ? 1 : 0)} ${units[unit]}`;
}

export default function LyricCreatorSection({ query, sectionFilter }: Props) {
  const preferredModel = useStore($creatorAutoTimingLastModel);
  const [statuses, setStatuses] = useState<AutoTimingModuleStatus[]>([]);
  const [busy, setBusy] = useState<AutoTimingModelId | null>(null);
  const [operation, setOperation] = useState<"download" | "remove" | null>(null);
  const [progress, setProgress] = useState<AutoTimingInstallProgress | null>(null);
  const [error, setError] = useState("");
  const taskScopeRef = useRef(new CreatorTaskScope());
  const mountedRef = useRef(false);

  const refresh = async (force = false) => {
    try {
      if (force) await autoTimingModelRepository.getManifest(true);
      const next = await autoTimingModelRepository.list();
      if (!mountedRef.current) return;
      setStatuses(next);
      setError("");
    } catch (reason) {
      if (!mountedRef.current) return;
      setError(reason instanceof Error ? reason.message : "Could not load timing modules.");
    }
  };

  useEffect(() => {
    mountedRef.current = true;
    void refresh();
    return () => {
      mountedRef.current = false;
      taskScopeRef.current.cancel();
    };
  }, []);

  const install = async (id: AutoTimingModelId) => {
    const task = taskScopeRef.current.begin();
    setBusy(id);
    setOperation("download");
    setProgress(null);
    setError("");
    try {
      await autoTimingModelRepository.install(id, (next) => {
        if (task.isCurrent()) setProgress(next);
      }, task.signal);
      task.throwIfCancelled();
      await refresh();
      task.throwIfCancelled();
      toast.success(`${id === "fast" ? "Fast" : "Accurate"} timing module installed.`);
    } catch (reason) {
      if (!task.isCurrent() || (reason as Error)?.name === "AbortError") return;
      const message = reason instanceof Error ? reason.message : "The timing module could not be installed.";
      setError(message);
      toast.error(message);
    } finally {
      if (task.isCurrent()) {
        setBusy(null);
        setOperation(null);
        setProgress(null);
      }
    }
  };

  const remove = async (id: AutoTimingModelId) => {
    setBusy(id);
    setOperation("remove");
    setError("");
    try {
      await autoTimingModelRepository.remove(id);
      if (!mountedRef.current) return;
      await refresh();
      if (!mountedRef.current) return;
      toast.success(`${id === "fast" ? "Fast" : "Accurate"} timing module removed.`);
    } catch (reason) {
      if (!mountedRef.current) return;
      const message = reason instanceof Error ? reason.message : "The timing module could not be removed.";
      setError(message);
      toast.error(message);
    } finally {
      if (mountedRef.current) {
        setBusy(null);
        setOperation(null);
      }
    }
  };

  if (sectionFilter !== "All" && sectionFilter !== SECTION_NAME) return null;
  const showPreference = matches(
    query,
    "Preferred Auto-time module",
    "Remember the most recently used on-device timing model."
  );
  const visibleStatuses = statuses.filter((status) =>
    matches(query, `${status.definition.displayName} timing module`, status.definition.description)
  );
  const showError = Boolean(error) && matches(query, "Timing module status", error);
  if (!showPreference && visibleStatuses.length === 0 && !showError && statuses.length > 0) return null;

  return (
    <>
      <SectionTitle>Lyric Creator</SectionTitle>
      {showPreference && (
        <Row
          label="Preferred Auto-time module"
          description="Auto-time remembers the module used by the last successful timing job."
        >
          <Select
            value={preferredModel}
            options={["fast", "accurate"]}
            labels={["Fast", "Accurate"]}
            onChange={(value) => $creatorAutoTimingLastModel.set(value as AutoTimingModelId)}
          />
        </Row>
      )}
      {visibleStatuses.map((status) => {
        const installing = busy === status.definition.id;
        const percent = installing ? Math.round((progress?.progress ?? 0) * 100) : 0;
        return (
          <Row
            key={status.definition.id}
            label={`${status.definition.displayName} timing module`}
            description={`${status.definition.description} · ${formatBytes(status.definition.size)} · Audio stays on this device.`}
            stacked
          >
            <div className="il-sp-model-actions">
              <span className={`il-sp-model-status${status.installed ? " is-installed" : ""}`}>
                {installing
                  ? operation === "remove" ? "Removing…" : `${percent}%`
                  : status.updateAvailable
                    ? "Update available"
                    : status.installed
                      ? `Installed · ${status.installedVersion}`
                      : "Not installed"}
              </span>
              <button
                type="button"
                className="il-sp-btn"
                disabled={busy !== null}
                onClick={() => void (status.installed ? remove(status.definition.id) : install(status.definition.id))}
              >
                {status.installed ? "Uninstall" : "Download"}
              </button>
              {status.updateAvailable && (
                <button
                  type="button"
                  className="il-sp-btn"
                  disabled={busy !== null}
                  onClick={() => void install(status.definition.id)}
                >
                  Update
                </button>
              )}
              {installing && operation === "download" && (
                <button
                  type="button"
                  className="il-sp-btn"
                  onClick={() => {
                    taskScopeRef.current.cancel();
                    setBusy(null);
                    setOperation(null);
                    setProgress(null);
                  }}
                >
                  Cancel download
                </button>
              )}
            </div>
          </Row>
        );
      })}
      {statuses.length === 0 && !error && (
        <Row label="Timing modules" description="Checking optional on-device modules…">
          <span className="il-sp-model-status">Loading…</span>
        </Row>
      )}
      {showError && (
        <Row label="Timing module status" description={error}>
          <button type="button" className="il-sp-btn" onClick={() => void refresh(true)}>
            Retry
          </button>
        </Row>
      )}
    </>
  );
}
