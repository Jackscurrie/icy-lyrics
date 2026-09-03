import React from "react";

type IconButtonProps = {
  onClick?: () => void;
  icon?: React.ReactNode;
  label?: string;
  variant?: "default" | "primary" | "danger";
  disabled?: boolean;
  title?: string;
  className?: string;
  children?: React.ReactNode;
};

export function IconButton({
  onClick,
  icon,
  label,
  variant = "default",
  disabled,
  title,
  className,
  children,
}: IconButtonProps) {
  return (
    <button
      className={`il-ldb-icon-btn il-ldb-icon-btn--${variant}${className ? ` ${className}` : ""}`}
      onClick={onClick}
      disabled={disabled}
      title={title}
      aria-label={title ?? label}
      type="button"
    >
      {icon && <span className="il-ldb-icon-btn__icon">{icon}</span>}
      {(label || children) && (
        <span className="il-ldb-icon-btn__label">{label ?? children}</span>
      )}
    </button>
  );
}
