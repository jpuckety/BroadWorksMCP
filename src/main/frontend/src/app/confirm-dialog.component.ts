import { Component, EventEmitter, Input, Output } from '@angular/core';

/**
 * Modal confirm used for destructive actions. Chrome lives in `styles.css` so this stays
 * within the component style budget.
 */
@Component({
  selector: 'app-confirm-dialog',
  template: `
    @if (isOpen) {
      <div class="modal-overlay" (click)="onCancel()" role="presentation">
        <div
          class="modal-content"
          role="dialog"
          aria-modal="true"
          [attr.aria-labelledby]="titleId"
          (click)="$event.stopPropagation()"
        >
          <div class="dialog-icon-wrapper">
            <div class="dialog-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M3 6h18"/>
                <path d="M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6"/>
                <path d="M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2"/>
                <line x1="10" y1="11" x2="10" y2="17"/>
                <line x1="14" y1="11" x2="14" y2="17"/>
              </svg>
            </div>
          </div>

          <h3 class="dialog-title" [id]="titleId">{{ title }}</h3>
          <p class="dialog-message">{{ message }}</p>

          <div class="dialog-actions">
            <button type="button" class="btn btn-secondary" (click)="onCancel()" [disabled]="loading">
              Cancel
            </button>
            <button type="button" class="btn btn-danger" (click)="onConfirm()" [disabled]="loading">
              {{ loading ? 'Working…' : confirmText }}
            </button>
          </div>
        </div>
      </div>
    }
  `
})
export class ConfirmDialogComponent {
  @Input() isOpen = false;
  @Input() title = 'Confirm';
  @Input() message = 'Are you sure? This action cannot be undone.';
  @Input() confirmText = 'Confirm';
  @Input() loading = false;

  @Output() readonly confirm = new EventEmitter<void>();
  @Output() readonly cancel = new EventEmitter<void>();

  protected readonly titleId = 'confirm-dialog-title';

  protected onConfirm(): void {
    if (this.loading) {
      return;
    }
    this.confirm.emit();
  }

  protected onCancel(): void {
    if (this.loading) {
      return;
    }
    this.cancel.emit();
  }
}
