import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AuthService } from './auth.service';
import { LoginComponent } from './login.component';

describe('LoginComponent', () => {
  let fixture: ComponentFixture<LoginComponent>;
  let loginWithGoogle: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    loginWithGoogle = vi.fn();
    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [
        provideRouter([]),
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: () => false,
            me: () => of(null),
            consumeReturnUrl: () => null,
            loginWithGoogle
          }
        },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { queryParamMap: { get: (key: string) => key === 'returnUrl' ? '/approvals/a1' : null } }
          }
        }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(LoginComponent);
    await fixture.whenStable();
    fixture.detectChanges();
  });

  it('renders Continue with Google and no token field', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('Continue with Google');
    expect(compiled.textContent).toContain('BroadWorks Credentials Portal');
    expect(compiled.querySelector('input')).toBeNull();
    expect(compiled.querySelector('form')).toBeNull();
    expect(compiled.textContent).not.toMatch(/access token/i);
  });

  it('passes the return URL to Google login', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    const button = compiled.querySelector<HTMLButtonElement>('.btn-google');
    expect(button?.textContent).toContain('Continue with Google');
    button?.click();
    expect(loginWithGoogle).toHaveBeenCalledWith('/approvals/a1');
  });
});

describe('LoginComponent already authenticated', () => {
  it('redirects to the home route', async () => {
    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [
        provideRouter([]),
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: () => true,
            me: () => of({ email: 'user@example.com', name: 'User', picture: null }),
            consumeReturnUrl: () => null,
            loginWithGoogle: vi.fn()
          }
        }
      ]
    }).compileComponents();

    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);

    TestBed.createComponent(LoginComponent);

    expect(navigate).toHaveBeenCalledWith('/');
  });
});
