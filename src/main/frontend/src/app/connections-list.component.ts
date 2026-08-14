import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ConnectionResponse } from './connection';
import { ConnectionsService } from './connections.service';

/**
 * Lists the signed-in user's BroadWorks connections, flags any that still need a password, and offers
 * edit / set-password / delete actions plus a link to create a new connection.
 */
@Component({
  selector: 'app-connections-list',
  imports: [RouterLink],
  template: `
    <section class="panel">
      <div class="panel-header">
        <h2>BroadWorks Connections</h2>
        <a class="btn primary" routerLink="/new">Add connection</a>
      </div>

      @if (error()) {
        <p class="error" role="alert">{{ error() }}</p>
      }

      @if (loading()) {
        <p>Loading…</p>
      } @else if (connections().length === 0) {
        <p class="empty">You have no BroadWorks connections yet.</p>
      } @else {
        <table class="grid">
          <thead>
            <tr>
              <th>Name</th><th>Host</th><th>Port</th><th>User</th>
              <th>Status</th><th class="actions">Actions</th>
            </tr>
          </thead>
          <tbody>
            @for (c of connections(); track c.resourceId) {
              <tr>
                <td>{{ c.displayName }}</td>
                <td>{{ c.hostname }}</td>
                <td>{{ c.port }}</td>
                <td>{{ c.username }}</td>
                <td>
                  @if (c.needsPassword) {
                    <span class="badge warn">Needs password</span>
                  } @else {
                    <span class="badge ok">Ready</span>
                  }
                </td>
                <td class="actions">
                  <a class="btn" [routerLink]="['/', c.resourceId, 'edit']">Edit</a>
                  <a class="btn" [routerLink]="['/', c.resourceId, 'password']">Set password</a>
                  <button class="btn danger" type="button" (click)="remove(c)">Delete</button>
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
    </section>
  `
})
export class ConnectionsListComponent {
  private readonly service = inject(ConnectionsService);

  protected readonly connections = signal<ConnectionResponse[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.reload();
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

  protected remove(connection: ConnectionResponse): void {
    if (!confirm(`Delete connection "${connection.displayName}"?`)) {
      return;
    }
    this.service.delete(connection.resourceId).subscribe({
      next: () => this.reload(),
      error: () => this.error.set('Failed to delete connection.')
    });
  }
}
