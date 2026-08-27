import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ConnectionRequest, VerifyConnectionRequest, VerifyResult } from './connection';
import { ConnectionsService } from './connections.service';

/**
 * Create or edit a connection's non-secret fields. In edit mode the password field is left blank and,
 * if left blank on save, the stored secret is unchanged (a non-blank value updates it). Stored
 * passwords are never fetched into this form.
 */
@Component({
  selector: 'app-connection-form',
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <div class="container">
      <div class="page-header">
        <div>
          <h2>{{ editing() ? 'Edit connection' : 'Add connection' }}</h2>
          <p class="page-subtitle">
            OCI host, port, and username. Passwords stay encrypted at rest and are never returned.
          </p>
        </div>
        <a class="btn btn-secondary" routerLink="/">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16" aria-hidden="true">
            <line x1="19" y1="12" x2="5" y2="12"/>
            <polyline points="12 19 5 12 12 5"/>
          </svg>
          Back
        </a>
      </div>

      @if (error()) {
        <p class="alert alert-danger" role="alert">{{ error() }}</p>
      }

      @if (verifyResult(); as result) {
        <p
          class="alert"
          [class.alert-success]="result.success"
          [class.alert-danger]="!result.success"
          role="status"
        >
          {{ result.message }}
        </p>
      }

      @if (loading()) {
        <div class="loading-state">
          <div class="spinner"></div>
          <p>Loading connection…</p>
        </div>
      } @else {
        <form [formGroup]="form" (ngSubmit)="save()" class="form-wrapper">
          <section class="card">
            <h3 class="card-section-title">Connection details</h3>
            <p class="section-help">How this OCI host appears in the portal and which server MCP tools use.</p>

            <div class="form-group">
              <label class="form-label" for="displayName">Display name</label>
              <input
                id="displayName"
                class="form-input"
                type="text"
                formControlName="displayName"
                autocomplete="off"
              />
            </div>

            <div class="form-row">
              <div class="form-group flex-2">
                <label class="form-label" for="hostname">Hostname</label>
                <input
                  id="hostname"
                  class="form-input"
                  type="text"
                  formControlName="hostname"
                  autocomplete="off"
                  placeholder="portal.example.com"
                />
              </div>
              <div class="form-group flex-1">
                <label class="form-label" for="port">Port</label>
                <input
                  id="port"
                  class="form-input"
                  type="number"
                  formControlName="port"
                  min="1"
                  max="65535"
                />
              </div>
            </div>

            <div class="form-group">
              <label class="form-label" for="username">Username</label>
              <input
                id="username"
                class="form-input"
                type="text"
                formControlName="username"
                autocomplete="off"
              />
            </div>
          </section>

          <section class="card">
            <h3 class="card-section-title">Credentials</h3>
            <p class="section-help">
              {{ editing() ? 'Leave blank to keep the stored password.' : 'Optional — you can set the password later.' }}
            </p>
            <div class="form-group">
              <label class="form-label" for="password">Password</label>
              <input
                id="password"
                class="form-input"
                type="password"
                formControlName="password"
                autocomplete="new-password"
              />
            </div>
          </section>

          <section class="card action-card">
            <div class="verify-section">
              <div class="verify-info">
                <h4 class="verify-title">Test connection</h4>
                <p class="verify-desc">
                  Try an OCI login with these host settings. New connections need a password first;
                  existing ones can use the stored secret.
                </p>
              </div>
              <button
                class="btn btn-secondary"
                type="button"
                (click)="verify()"
                [disabled]="!canVerify()"
              >
                {{ verifying() ? 'Verifying…' : 'Verify' }}
              </button>
            </div>

            <hr class="action-divider" />

            <div class="form-actions">
              <a class="btn btn-secondary" routerLink="/">Cancel</a>
              <button class="btn btn-primary" type="submit" [disabled]="form.invalid || saving()">
                {{ saving() ? 'Saving…' : (editing() ? 'Update connection' : 'Save connection') }}
              </button>
            </div>
          </section>
        </form>
      }
    </div>
  `
})
export class ConnectionFormComponent {
  private readonly fb = inject(FormBuilder);
  private readonly service = inject(ConnectionsService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  private readonly id = this.route.snapshot.paramMap.get('id');

  protected readonly editing = signal(this.id != null);
  protected readonly loading = signal(this.id != null);
  protected readonly saving = signal(false);
  protected readonly verifying = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly verifyResult = signal<VerifyResult | null>(null);

  protected readonly form = this.fb.nonNullable.group({
    displayName: ['', Validators.required],
    hostname: ['', Validators.required],
    port: [2208, [Validators.required, Validators.min(1), Validators.max(65535)]],
    username: ['', Validators.required],
    password: ['']
  });

  constructor() {
    if (this.id) {
      this.service.get(this.id).subscribe({
        next: (c) => {
          this.form.patchValue({
            displayName: c.displayName,
            hostname: c.hostname,
            port: c.port,
            username: c.username,
            password: ''
          });
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.error.set('Failed to load the connection.');
        }
      });
    }
  }

  /**
   * Whether the connection can be tested right now: the target fields must be valid and there must be
   * a credential to try — a password entered in the form, or (when editing) the connection's stored
   * secret. New connections therefore require a password before the button enables.
   */
  protected canVerify(): boolean {
    if (this.form.invalid || this.verifying()) {
      return false;
    }
    return this.editing() || !!this.form.getRawValue().password;
  }

  protected verify(): void {
    if (!this.canVerify()) {
      return;
    }
    this.verifying.set(true);
    this.error.set(null);
    this.verifyResult.set(null);

    const value = this.form.getRawValue();
    const request: VerifyConnectionRequest = {
      hostname: value.hostname,
      port: value.port,
      username: value.username,
      password: value.password ? value.password : undefined,
      resourceId: this.id ?? undefined
    };

    this.service.verify(request).subscribe({
      next: (result) => {
        this.verifying.set(false);
        this.verifyResult.set(result);
      },
      error: (err) => {
        this.verifying.set(false);
        this.verifyResult.set({
          success: false,
          message: err?.error?.message ?? 'Failed to verify the connection.'
        });
      }
    });
  }

  protected save(): void {
    if (this.form.invalid) {
      return;
    }
    this.saving.set(true);
    this.error.set(null);

    const value = this.form.getRawValue();
    const request: ConnectionRequest = {
      displayName: value.displayName,
      hostname: value.hostname,
      port: value.port,
      username: value.username,
      password: value.password ? value.password : undefined
    };

    const call = this.id
      ? this.service.update(this.id, request)
      : this.service.create(request);

    call.subscribe({
      next: () => this.router.navigate(['/']),
      error: (err) => {
        this.saving.set(false);
        this.error.set(err?.error?.message ?? 'Failed to save the connection.');
      }
    });
  }
}
