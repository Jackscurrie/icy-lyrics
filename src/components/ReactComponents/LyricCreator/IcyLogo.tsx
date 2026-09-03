import React from "react";
import { ICY_LYRICS_ICE_ACCENT } from "../../Styling/Icons.ts";

interface IcyLogoProps {
  className?: string;
  title?: string;
}

/** The same two-tone frozen-microphone mark used by Icy Lyrics' navigation entry. */
export default function IcyLogo({ className, title = "Icy Lyrics" }: IcyLogoProps) {
  return (
    <svg
      className={className}
      role="img"
      aria-label={title}
      viewBox="0 0 24 24"
      xmlns="http://www.w3.org/2000/svg"
    >
      <path
        fill="currentColor"
        d="M18.996 5.004a3.64 3.64 0 0 0-6.186 1.998l4.187 4.188a3.64 3.64 0 0 0 1.999-6.186Zm-3.773 7.15-3.378-3.38-7.5 8.54a1.667 1.667 0 1 0 2.341 2.342l3.947-3.467 4.59-4.035Zm-4.36-5.19a5.64 5.64 0 1 1 6.173 6.172l-5.125 4.504-3.947 3.468a3.668 3.668 0 0 1-5.072-5.072l3.451-3.927 4.52-5.145Z"
      />
      <path
        fill={ICY_LYRICS_ICE_ACCENT}
        d="M15.1 12.2 14.56 15.55c-.05.34-.49.42-.65.11l-.56-1.99 1.75-1.47Z"
      />
      <path
        fill={ICY_LYRICS_ICE_ACCENT}
        d="M13.08 13.98 12.53 18.54c-.04.36-.52.44-.67.11l-.83-2.64 2.05-2.03Z"
      />
      <path
        fill={ICY_LYRICS_ICE_ACCENT}
        d="M10.83 15.96 10.33 19.99c-.04.34-.49.43-.66.13l-.83-2.34 1.99-1.82Z"
      />
      <g fill="none" stroke={ICY_LYRICS_ICE_ACCENT} strokeWidth={1.05} strokeLinecap="round">
        <path d="M7.2.9v4.6M5.2 2.05l4 2.3M5.2 4.35l4-2.3" />
        <path d="M20 16.9v4.2M18.18 17.95l3.64 2.1M18.18 20.05l3.64-2.1" />
      </g>
    </svg>
  );
}
