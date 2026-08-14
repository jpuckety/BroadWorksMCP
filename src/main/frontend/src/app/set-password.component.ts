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
    <section class="panel">
      <div class="panel-header">
        <h2>Set password</h2>
        <a class="btn" routerLink="/">Back</a>
      </div>

      <p class="hint">
        Set the BroadWorks password for this connection. It is stored encrypted and never shown again
        or shared with the AI agent.
      </p>

      @if (error()) {
        <p class="error" role="alert">{{ error() }}</p>
      }

      <form [formGroup]="form" (ngSubmit)="save()" class="form">
        <label>
          <span>New password</span>
          <input type="password" formControlName="password" autocomplete="new-password" />
        </label>
        <div class="form-actions">
          <button class="btn primary" type="submit" [disabled]="form.invalid || saving()">
            {{ saving() ? 'Saving…' : 'Save password' }}
          </button>
          <a class="btn" routerLink="/">Cancel</a>
        </div>
      </form>
    </section>
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
