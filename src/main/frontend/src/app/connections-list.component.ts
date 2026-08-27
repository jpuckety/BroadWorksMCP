import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ConfirmDialogComponent } from './confirm-dialog.component';
import { ConnectionResponse, VerifyResult } from './connection';
import { ConnectionsService } from './connections.service';

/**
 * Lists the signed-in user's BroadWorks connections as cards, flags any that still need a password,
 * and offers edit / verify / delete. Delete goes through a confirm dialog, not `window.confirm`.
 */
@Component({
  selector: 'app-connections-list',
  imports: [RouterLink, ConfirmDialogComponent],
  template: `
    <div class="container">
      <div class="page-header">
        <div>
          <h2>BroadWorks Connections</h2>
          <p class="page-subtitle">Manage OCI hosts used by MCP tools. Passwords stay encrypted at rest.</p>
        </div>
        <a class="btn btn-primary" routerLink="/new">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16" aria-hidden="true">
            <line x1="12" y1="5" x2="12" y2="19"/>
            <line x1="5" y1="12" x2="19" y2="12"/>
          </svg>
          Add connection
        </a>
      </div>

      @if (error()) {
        <p class="alert alert-danger" role="alert">{{ error() }}</p>
      }

      @if (loading()) {
        <div class="loading-state">
          <div class="spinner"></div>
          <p>Loading BroadWorks connections…</p>
        </div>
      } @else if (connections().length === 0) {
        <div class="empty-state card">
          <div class="empty-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <rect width="20" height="8" x="2" y="2" rx="2" ry="2"/>
              <rect width="20" height="8" x="2" y="14" rx="2" ry="2"/>
              <line x1="6" x2="6.01" y1="6" y2="6"/>
              <line x1="6" x2="6.01" y1="18" y2="18"/>
            </svg>
          </div>
          <h3>No BroadWorks connections yet</h3>
          <p>
            Add an OCI host so MCP tools can sign in. Passwords are stored encrypted at rest and are
            never returned to the browser.
          </p>
          <a class="btn btn-primary" routerLink="/new">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16" aria-hidden="true">
              <line x1="12" y1="5" x2="12" y2="19"/>
              <line x1="5" y1="12" x2="19" y2="12"/>
            </svg>
            Add connection
          </a>
        </div>
      } @else {
        <div class="connections-grid">
          @for (c of connections(); track c.resourceId) {
            <article class="connection-card card">
              <div class="connection-card-header">
                <div>
                  <div class="connection-title-row">
                    <h3 class="connection-name">{{ c.displayName }}</h3>
                    @if (c.needsPassword) {
                      <span class="badge badge-warn">Needs password</span>
                    }
                  </div>
                  <p class="connection-endpoint">{{ c.username }}@{{ c.hostname }}:{{ c.port }}</p>
                </div>
                <div class="connection-actions">
                  <a class="btn btn-secondary btn-sm" [routerLink]="['/', c.resourceId, 'edit']">Edit</a>
                  <button
                    class="btn btn-secondary btn-sm"
                    type="button"
                    (click)="verify(c)"
                    [disabled]="verifyingId() === c.resourceId"
                  >
                    {{ verifyingId() === c.resourceId ? 'Verifying…' : 'Verify' }}
                  </button>
                  <button class="btn btn-outline-danger btn-sm" type="button" (click)="openDeleteDialog(c)">
                    Delete
                  </button>
                </div>
              </div>

              @if (verifyResults()[c.resourceId]; as result) {
                <p
                  class="alert"
                  [class.alert-success]="result.success"
                  [class.alert-danger]="!result.success"
                  role="status"
                >
                  {{ result.message }}
                </p>
              }
            </article>
          }
        </div>
      }

      <app-confirm-dialog
        [isOpen]="pendingDelete() !== null"
        title="Delete connection"
        [message]="deleteMessage()"
        confirmText="Delete"
        [loading]="deleting()"
        (confirm)="confirmDelete()"
        (cancel)="closeDeleteDialog()"
      />
    </div>
  `
})
export class ConnectionsListComponent {
  private readonly service = inject(ConnectionsService);

  protected readonly connections = signal<ConnectionResponse[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly pendingDelete = signal<ConnectionResponse | null>(null);
  protected readonly deleting = signal(false);
  protected readonly verifyingId = signal<string | null>(null);
  protected readonly verifyResults = signal<Record<string, VerifyResult>>({});

  constructor() {
    this.reload();
  }

  protected deleteMessage(): string {
    const name = this.pendingDelete()?.displayName ?? 'this connection';
    return `Delete connection "${name}"? This removes the stored OCI credentials and cannot be undone.`;
  }

  protected openDeleteDialog(connection: ConnectionResponse): void {
    this.pendingDelete.set(connection);
  }

  protected closeDeleteDialog(): void {
    if (this.deleting()) {
      return;
    }
    this.pendingDelete.set(null);
  }

  protected confirmDelete(): void {
    const connection = this.pendingDelete();
    if (!connection || this.deleting()) {
      return;
    }
    this.deleting.set(true);
    this.error.set(null);
    this.service.delete(connection.resourceId).subscribe({
      next: () => {
        this.deleting.set(false);
        this.pendingDelete.set(null);
        this.reload();
      },
      error: () => {
        this.deleting.set(false);
        this.pendingDelete.set(null);
        this.error.set('Failed to delete connection.');
      }
    });
  }

  protected verify(connection: ConnectionResponse): void {
    this.verifyingId.set(connection.resourceId);
    this.error.set(null);
    this.verifyResults.update((current) => {
      const next = { ...current };
      delete next[connection.resourceId];
      return next;
    });
    this.service.verify({
      hostname: connection.hostname,
      port: connection.port,
      username: connection.username,
      resourceId: connection.resourceId
    }).subscribe({
      next: (result) => {
        this.verifyingId.set(null);
        this.verifyResults.update((current) => ({ ...current, [connection.resourceId]: result }));
      },
      error: (err) => {
        this.verifyingId.set(null);
        this.verifyResults.update((current) => ({
          ...current,
          [connection.resourceId]: {
            success: false,
            message: err?.error?.message ?? 'Failed to verify the connection.'
          }
        }));
      }
    });
  }

  private reload(): void {
    this.loading.set(true);
    this.error.set(null);
    this.service.list().subscribe({
      next: (list) => {
        this.connections.set(list);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Failed to load connections.');
        this.loading.set(false);
      }
    });
  }
}
