type ModalDisplayOptions = {
	title: string;
	content: any;
	isLarge?: boolean;
	onClose?: (() => void) | null;
	closeBtn?: boolean;
	closeOnOutsideClick?: boolean;
	/** Replaces the default hide() behavior for the close button and outside-click. */
	closeHandler?: (() => void) | null;
	/** Optional class appended to `.il-modal` for per-modal styling/identification. */
	modalId?: string | null;
};

type ModalTransitionOptions = {
	content: any;
	onClose?: (() => void) | null;
	closeHandler?: (() => void) | null;
	/** Optional class appended to `.il-modal`. Replaces any previously set modalId class. */
	modalId?: string | null;
	/** Optional new header title. Omit to keep the current one. */
	title?: string | null;
};

const ICY_LYRICS_MODAL_TAG = "icy-lyrics-modal";

class _HTMLIcyLyricsModal extends HTMLElement {
	private _onClose: (() => void) | null;
	private _currentModalId: string | null;

	constructor() {
		super();
		this._onClose = null;
		this._currentModalId = null;
	}

	private _applyModalId(modalId: string | null | undefined): void {
		const modalEl = this.querySelector(".il-modal");
		if (this._currentModalId && modalEl) {
			modalEl.classList.remove(this._currentModalId);
		}
		const nextId = typeof modalId === "string" && modalId.length > 0 ? `ilmodal-${modalId}` : null;
		if (nextId && modalEl) {
			modalEl.classList.add(nextId);
		}
		this._currentModalId = nextId;
	}

	hide(): void {
        const capturedOnClose = this._onClose;
        this._onClose = null;
        this._currentModalId = null;
        const _removeFromDom = (timeoutDuration: number) => {
            setTimeout(() => {
                this?.remove();
                if (typeof capturedOnClose === "function") {
                    capturedOnClose();
                }
            }, timeoutDuration)
        };

        const genericModal = this?.querySelector(".il-modal-overlay-animated");
        if (genericModal) {
            genericModal.classList.remove("Active");
            _removeFromDom((0.22 * 1000) + 30);
        } else {
            _removeFromDom(0);
        }
	}

	/**
	 * Instantly swap modal content without hiding/re-animating.
	 * Use for modal-to-modal transitions where the frame should stay visible.
	 */
	transition({ content, onClose = null, closeHandler = null, modalId = null, title = null }: ModalTransitionOptions): void {
		if (typeof this._onClose === "function") {
			this._onClose();
		}
		this._onClose = onClose;
		const closeButton = this.querySelector(".il-modal-close-btn");
		if (closeButton) {
			(closeButton as HTMLButtonElement).onclick = closeHandler ?? this.hide.bind(this);
		}
		if (typeof title === "string") {
			const titleEl = this.querySelector(".il-modal-title");
			if (titleEl) titleEl.textContent = title;
		}
		this._applyModalId(modalId);
		const main = this.querySelector("main");
		if (main) {
			main.innerHTML = "";
			if (typeof content === "string") {
				main.innerHTML = content;
			} else if (content instanceof Node) {
				main.append(content);
			}
		}
	}

	/**
	 * Display the modal.
	 * @param {Object} options
	 * @param {string} options.title
	 * @param {any} options.content
	 * @param {boolean} [options.isLarge]
	 * @param {function} [options.onClose] - Optional callback to run when modal is closed
	 * @param {boolean} [options.closeBtn=true] - Show modal close button
	 * @param {boolean} [options.closeOnOutsideClick=true] - Allow closing modal by clicking outside
	 */
	display({ title, content, isLarge = false, onClose = null, closeBtn = true, closeOnOutsideClick = true, closeHandler = null, modalId = null }: ModalDisplayOptions): void {
		// If a previous onClose exists, call it before displaying a new popup
		if (typeof this._onClose === "function") {
			this._onClose();
		}
		this._onClose = onClose;
		// Reset tracked modalId since innerHTML below replaces the previous `.il-modal` element.
		this._currentModalId = null;
		this.innerHTML = `
<div class="il-modal-overlay il-modal-overlay-animated" style="z-index: 100;">
	<div class="il-modal" tabindex="-1" role="dialog" aria-label="${title}" aria-modal="true">
		<div class="${isLarge ? "il-modal-container-large" : "il-modal-container"}">
			<div class="il-modal-header">
				<h1 class="il-modal-title" as="h1">${title}</h1>
				${closeBtn ? '<button aria-label="Close" class="il-modal-close-btn"><svg width="18" height="18" viewBox="0 0 32 32" xmlns="http://www.w3.org/2000/svg"><title>Close</title><path d="M31.098 29.794L16.955 15.65 31.097 1.51 29.683.093 15.54 14.237 1.4.094-.016 1.508 14.126 15.65-.016 29.795l1.414 1.414L15.54 17.065l14.144 14.143" fill="currentColor" fill-rule="evenodd"></path></svg></button>' : ""}
			</div>
			<div class="il-modal-main-section">
				<main class="il-modal-content"></main>
			</div>
		</div>
	</div>
</div>`;

		const closeButton = this.querySelector("button");
		if (closeButton) {
			(closeButton as HTMLButtonElement).onclick = closeHandler ?? this.hide.bind(this);
		}
		this._applyModalId(modalId);
		const main = this.querySelector("main");
		const hidePopup = closeHandler ?? this.hide.bind(this);

		// Listen for click events on Overlay
		const overlay = this.querySelector(".il-modal-overlay");
		if (overlay) {
			overlay.addEventListener("click", (event: MouseEvent) => {
				if (closeOnOutsideClick && event.target === event.currentTarget) hidePopup();
			});
		}

		if (main) {
			if (typeof content === "string") {
				main.innerHTML = content;
			} else if (content instanceof Node) {
				main.append(content);
			} else if (content !== null && content !== undefined) {
				main.append(String(content));
			}
		}
		document.body.append(this);

        setTimeout(() => {
            const genericModal = this.querySelector(".il-modal-overlay-animated");
            if (genericModal) genericModal.classList.add("Active");
        }, 50);
	}
}

if (!customElements.get(ICY_LYRICS_MODAL_TAG)) {
	customElements.define(ICY_LYRICS_MODAL_TAG, _HTMLIcyLyricsModal);
}

export const PopupModal = document.createElement(ICY_LYRICS_MODAL_TAG) as _HTMLIcyLyricsModal;
// Custom-element constructors must not add attributes. Chrome rejects the
// element construction if the constructor mutates classList, so apply the
// styling hook only after document.createElement() has completed.
PopupModal.classList.add("IcyLyricsModal");
