import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ConnectionRequest } from './connection';
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
          <span>Login type</span>
          <select formControlName="loginType">
            <option value="SYSTEM">SYSTEM</option>
            <option value="PROVISIONING">PROVISIONING</option>
            <option value="SERVICEPROVIDER">SERVICEPROVIDER</option>
          </select>
        </label>
        <label class="checkbox">
          <input type="checkbox" formControlName="usePrivateApplicationServerAddress" />
          <span>Use private application server address</span>
        </label>
        <label>
          <span>Password {{ editing() ? '(leave blank to keep current)' : '(optional — can be set later)' }}</span>
          <input type="password" formControlName="password" autocomplete="new-password" />
        </label>

        <div class="form-actions">
          <button class="btn primary" type="submit" [disabled]="form.invalid || saving()">
            {{ saving() ? 'Saving…' : 'Save' }}
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
  protected readonly error = signal<string | null>(null);

  protected readonly form = this.fb.nonNullable.group({
    displayName: ['', Validators.required],
    hostname: ['', Validators.required],
    port: [2208, [Validators.required, Validators.min(1), Validators.max(65535)]],
    username: ['', Validators.required],
    loginType: ['SYSTEM'],
    usePrivateApplicationServerAddress: [false],
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
          loginType: c.loginType,
          usePrivateApplicationServerAddress: c.usePrivateApplicationServerAddress,
          password: ''
        }),
        error: () => this.error.set('Failed to load the connection.')
      });
    }
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
      loginType: value.loginType,
      usePrivateApplicationServerAddress: value.usePrivateApplicationServerAddress,
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
