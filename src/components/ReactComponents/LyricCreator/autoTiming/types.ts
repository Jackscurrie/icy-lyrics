import type { CreatorProject } from "../model.ts";

export type AutoTimingModelId = "fast" | "accurate";
export type AutoTimingConfidence = "high" | "medium" | "low" | "estimated";
export type AutoTimingMethod = "recognized" | "aligned" | "interpolated";
export type AutoTimingApplyMode = "missing" | "replace";

export interface AutoTimingWord {
  text: string;
  startTimeMs: number;
  endTimeMs: number;
  /** A missing endpoint was approximated; this is not acoustic word timing. */
  estimatedTiming?: boolean;
}

export interface AutoTimingEnergyFrame {
  timeMs: number;
  energy: number;
}

export interface AutoTimingFragmentCandidate {
  fragmentId: string;
  lineId: string;
  lineIndex: number;
  tokenIndex: number;
  fragmentIndex: number;
  startTimeMs: number;
  endTimeMs: number;
  confidence: AutoTimingConfidence;
  method: AutoTimingMethod;
  score: number;
}

export interface AutoTimingCandidate {
  projectFingerprint: string;
  modelId: AutoTimingModelId;
  modelVersion: string;
  durationMs: number;
  createdAt: number;
  fragments: Record<string, AutoTimingFragmentCandidate>;
  transcriptWordCount: number;
  /** Lines with acoustic word evidence, including words split into fragments. */
  supportedLineIds?: string[];
}

export type AutoTimingProgressPhase =
  | "preparing"
  | "downloading"
  | "loading-model"
  | "transcribing"
  | "aligning"
  | "review";

export interface AutoTimingProgress {
  phase: AutoTimingProgressPhase;
  progress: number;
  message: string;
}

export interface AutoTimingRunInput {
  project: CreatorProject;
  projectFingerprint: string;
  modelId: AutoTimingModelId;
  modelVersion: string;
  durationMs: number;
  words: AutoTimingWord[];
  energyFrames?: AutoTimingEnergyFrame[];
}

export interface AutoTimingApplyOptions {
  mode: AutoTimingApplyMode;
  includedLineIds?: ReadonlySet<string>;
}

export interface AutoTimingModuleFile {
  path: string;
  url: string;
  size: number;
  sha256: string;
  parts?: Array<{
    url: string;
    size: number;
    sha256: string;
  }>;
}

export interface AutoTimingModuleDefinition {
  id: AutoTimingModelId;
  displayName: string;
  description: string;
  version: string;
  modelId: string;
  revision: string;
  dtype: "q4";
  size: number;
  files: AutoTimingModuleFile[];
}

export interface AutoTimingRuntimeDefinition {
  version: string;
  wasmBaseUrl: string;
  files: AutoTimingModuleFile[];
}

export interface AutoTimingManifestPayload {
  schemaVersion: 1;
  release: string;
  generatedAt: string;
  remoteHost: string;
  remotePathTemplate: string;
  runtime: AutoTimingRuntimeDefinition;
  modules: AutoTimingModuleDefinition[];
}

export interface AutoTimingManifest extends AutoTimingManifestPayload {
  signature: {
    algorithm: "Ed25519";
    keyId: string;
    value: string;
  };
}

export interface AutoTimingModuleStatus {
  definition: AutoTimingModuleDefinition;
  installed: boolean;
  verified: boolean;
  installedVersion: string | null;
  updateAvailable: boolean;
}

export interface AutoTimingInstallProgress {
  moduleId: AutoTimingModelId;
  file: string;
  loaded: number;
  total: number;
  progress: number;
}
