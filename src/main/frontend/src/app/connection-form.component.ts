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
    <section class="panel">
      <div class="panel-header">
        <h2>{{ editing() ? 'Edit connection' : 'Add connection' }}</h2>
        <a class="btn" routerLink="/">Back</a>
      </div>

      @if (error()) {
        <p class="error" role="alert">{{ error() }}</p>
      }

      @if (verifyResult(); as result) {
        <p [class.success]="result.success" [class.error]="!result.success" role="status">
          {{ result.message }}
        </p>
      }

      <form [formGroup]="form" (ngSubmit)="save()" class="form">
        <label>
          <span>Display name</span>
          <input type="text" formControlName="displayName" autocomplete="off" />
        </label>
        <label>
          <span>Hostname</span>
          <input type="text" formControlName="hostname" autocomplete="off" placeholder="portal.example.com" />
        </label>
        <label>
          <span>Port</span>
          <input type="number" formControlName="port" min="1" max="65535" />
        </label>
        <label>
          <span>Username</span>
          <input type="text" formControlName="username" autocomplete="off" />
        </label>
        <label>
          <span>Password {{ editing() ? '(leave blank to keep current)' : '(optional — can be set later)' }}</span>
          <input type="password" formControlName="password" autocomplete="new-password" />
        </label>

        <div class="form-actions">
          <button class="btn primary" type="submit" [disabled]="form.invalid || saving()">
            {{ saving() ? 'Saving…' : 'Save' }}
          </button>
          <button class="btn" type="button" (click)="verify()" [disabled]="!canVerify()">
            {{ verifying() ? 'Verifying…' : 'Verify' }}
          </button>
          <a class="btn" routerLink="/">Cancel</a>
        </div>
      </form>
    </section>
  `
})
export class ConnectionFormComponent {
  private readonly fb = inject(FormBuilder);
  private readonly service = inject(ConnectionsService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  private readonly id = this.route.snapshot.paramMap.get('id');

  protected readonly editing = signal(this.id != null);
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
        next: (c) => this.form.patchValue({
          displayName: c.displayName,
          hostname: c.hostname,
          port: c.port,
          username: c.username,
          password: ''
        }),
        error: () => this.error.set('Failed to load the connection.')
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
