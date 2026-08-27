import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { Approval, ApprovalDecision, ApprovalService } from './approval.service';

/**
 * Human Confirm/Deny page opened by URL-mode elicitation. A successful decision unblocks the
 * waiting MCP tool; the agent cannot approve the delete on its own.
 */
@Component({
  selector: 'app-approval',
  template: `
    <div class="container">
      <div class="page-header">
        <div>
          <h2>Confirm action</h2>
          <p class="page-subtitle">A BroadWorks tool is waiting for a human decision.</p>
        </div>
      </div>

      <section class="card">
        @if (error()) {
          <p class="alert alert-danger" role="alert">{{ error() }}</p>
        }

        @if (loading()) {
          <div class="loading-state">
            <div class="spinner"></div>
            <p>Loading approval…</p>
          </div>
        } @else if (approval(); as current) {
          <h3 class="card-section-title">Requested action</h3>
          <p class="section-help">A BroadWorks tool is waiting for you to confirm this action:</p>
          <p class="approval-action">{{ current.action }}</p>

          @if (success()) {
            <p class="alert alert-success" role="status">{{ success() }}</p>
          } @else if (current.status === 'PENDING') {
            <p class="hint">Confirm or deny below. After you choose, you can close this tab.</p>
            <div class="form-actions">
              <button class="btn btn-outline-danger" type="button" [disabled]="saving()" (click)="decide('DECLINED')">
                Deny
              </button>
              <button class="btn btn-primary" type="button" [disabled]="saving()" (click)="decide('APPROVED')">
                {{ saving() ? 'Saving…' : 'Confirm' }}
              </button>
            </div>
          } @else {
            <p class="alert alert-success" role="status">{{ alreadyDecidedMessage(current.status) }}</p>
          }
        }
      </section>
    </div>
  `
})
export class ApprovalComponent {
  private readonly service = inject(ApprovalService);
  private readonly route = inject(ActivatedRoute);

  private readonly id = this.route.snapshot.paramMap.get('id');

  protected readonly approval = signal<Approval | null>(null);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly success = signal<string | null>(null);

  constructor() {
    if (!this.id) {
      this.loading.set(false);
      this.error.set('This approval link is missing an id.');
      return;
    }
    this.service.get(this.id).subscribe({
      next: (approval) => {
        this.approval.set(approval);
        this.loading.set(false);
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(this.messageFrom(err, 'This approval was not found or has expired.'));
      }
    });
  }

  protected decide(decision: ApprovalDecision): void {
    if (!this.id || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.service.decide(this.id, decision).subscribe({
      next: (approval) => {
        this.approval.set(approval);
        this.saving.set(false);
        this.success.set(
          approval.status === 'APPROVED'
            ? 'Confirmed. You can close this tab. The waiting tool will resume.'
            : 'Denied. You can close this tab. The waiting tool will resume.');
      },
      error: (err) => {
        this.saving.set(false);
        this.error.set(this.messageFrom(err, 'Failed to record the decision.'));
      }
    });
  }

  protected alreadyDecidedMessage(status: string): string {
    return status === 'APPROVED'
      ? 'This action was already confirmed. You can close this tab.'
      : 'This action was already denied. You can close this tab.';
  }

  private messageFrom(err: { status?: number; error?: { message?: string } }, fallback: string): string {
    if (err?.status === 404) {
      return 'This approval was not found or has expired.';
    }
    if (err?.status === 409) {
      return 'This approval was already decided.';
    }
    return err?.error?.message ?? fallback;
  }
}
