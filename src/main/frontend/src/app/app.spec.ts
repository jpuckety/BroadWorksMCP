import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { AuthService } from './auth.service';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('hides the header and footer when signed out', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.app-header')).toBeNull();
    expect(compiled.querySelector('.app-footer')).toBeNull();
    expect(compiled.textContent).not.toContain('Sign out');
  });

  it('shows the user chip and Sign out when signed in', async () => {
    const fixture = TestBed.createComponent(App);
    const auth = TestBed.inject(AuthService);
    const http = TestBed.inject(HttpTestingController);
    auth.me().subscribe();
    http.expectOne('/api/portal/me').flush({
      email: 'user@example.com',
      name: 'User Name',
      picture: null
    });
    await fixture.whenStable();
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.brand-title')?.textContent).toContain('BroadWorks');
    expect(compiled.querySelector('.brand-badge')?.textContent).toContain('Portal');
    expect(compiled.querySelector('.user-email')?.textContent).toContain('user@example.com');
    expect(compiled.textContent).toContain('Sign out');
    expect(compiled.querySelector('.app-footer')).toBeTruthy();
  });
});
