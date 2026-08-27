import { Component, inject } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from './auth.service';

/**
 * Branded Google-only login. The return URL (query param from the auth guard) is stashed in
 * sessionStorage before the browser leaves for `/oauth2/authorization/google`.
 */
@Component({
  selector: 'app-login',
  template: `
    <div class="login-container">
      <div class="login-card">
        <div class="login-header">
          <div class="logo-circle" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <rect width="20" height="8" x="2" y="2" rx="2" ry="2"/>
              <rect width="20" height="8" x="2" y="14" rx="2" ry="2"/>
              <line x1="6" x2="6.01" y1="6" y2="6"/>
              <line x1="6" x2="6.01" y1="18" y2="18"/>
            </svg>
          </div>
          <h1>BroadWorks Credentials Portal</h1>
          <p class="subtitle">
            Sign in with Google to manage BroadWorks OCI connections used by MCP tools.
            Passwords stay encrypted at rest and are never returned to the browser.
          </p>
        </div>

        <div class="auth-actions">
          <button type="button" class="btn btn-google" (click)="continueWithGoogle()">
            <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
              <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"/>
              <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"/>
              <path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.06H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.94l2.85-2.22.81-.63z"/>
              <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.06l3.66 2.84c.87-2.6 3.3-4.52 6.16-4.52z"/>
            </svg>
            Continue with Google
          </button>
        </div>

        <div class="security-features">
          <div class="feature-item">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16" aria-hidden="true">
              <rect width="18" height="11" x="3" y="11" rx="2" ry="2"/>
              <path d="M7 11V7a5 5 0 0 1 10 0v4"/>
            </svg>
            <span>Passwords stored encrypted at rest</span>
          </div>
          <div class="feature-item">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16" aria-hidden="true">
              <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
            </svg>
            <span>Google sign-in — no bearer tokens in the browser</span>
          </div>
          <div class="feature-item">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16" aria-hidden="true">
              <circle cx="12" cy="12" r="10"/>
              <line x1="12" y1="8" x2="12" y2="12"/>
              <line x1="12" y1="16" x2="12.01" y2="16"/>
            </svg>
            <span>Connection passwords are never returned from the API</span>
          </div>
        </div>
      </div>
    </div>
  `
})
export class LoginComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  constructor() {
    if (this.auth.isAuthenticated()) {
      void this.router.navigateByUrl(this.auth.consumeReturnUrl() || '/');
      return;
    }
    this.auth.me().subscribe((user) => {
      if (user) {
        void this.router.navigateByUrl(this.auth.consumeReturnUrl() || '/');
      }
    });
  }

  protected continueWithGoogle(): void {
    this.auth.loginWithGoogle(this.route.snapshot.queryParamMap.get('returnUrl'));
  }
}
