import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ConnectionsService } from './connections.service';

/**
 * Dedicated screen for setting/updating a connection's password out-of-band from the AI agent. The
 * current password is never displayed; a non-blank value is required.
 */
@Component({
  selector: 'app-set-password',
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <div class="container">
      <div class="page-header">
        <div>
          <h2>Set password</h2>
          <p class="page-subtitle">
            Stored encrypted at rest and never shown again or shared with the AI agent.
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

      <section class="card">
        <h3 class="card-section-title">Connection password</h3>
        <p class="section-help">
          Set the BroadWorks password for this connection. The current value is never displayed.
        </p>

        @if (error()) {
          <p class="alert alert-danger" role="alert">{{ error() }}</p>
        }

        <form [formGroup]="form" (ngSubmit)="save()">
          <div class="form-group">
            <label class="form-label" for="password">New password</label>
            <input
              id="password"
              class="form-input"
              type="password"
              formControlName="password"
              autocomplete="new-password"
            />
          </div>
          <div class="form-actions">
            <a class="btn btn-secondary" routerLink="/">Cancel</a>
            <button class="btn btn-primary" type="submit" [disabled]="form.invalid || saving()">
              {{ saving() ? 'Saving…' : 'Save password' }}
            </button>
          </div>
        </form>
      </section>
    </div>
  `
})
export class SetPasswordComponent {
  private readonly fb = inject(FormBuilder);
  private readonly service = inject(ConnectionsService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  private readonly id = this.route.snapshot.paramMap.get('id')!;

  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = this.fb.nonNullable.group({
    password: ['', Validators.required]
  });

  protected save(): void {
    if (this.form.invalid) {
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.service.setPassword(this.id, this.form.getRawValue().password).subscribe({
      next: () => this.router.navigate(['/']),
      error: (err) => {
        this.saving.set(false);
        this.error.set(err?.error?.message ?? 'Failed to set the password.');
      }
    });
  }
}
