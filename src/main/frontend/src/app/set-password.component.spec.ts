import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { SetPasswordComponent } from './set-password.component';
import { ConnectionsService } from './connections.service';

describe('SetPasswordComponent', () => {
  let fixture: ComponentFixture<SetPasswordComponent>;
  let setPassword: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    setPassword = vi.fn(() => of({
      resourceId: 'c1',
      displayName: 'Office OCI',
      hostname: 'bw.example.com',
      port: 2208,
      username: 'admin',
      needsPassword: false
    }));

    await TestBed.configureTestingModule({
      imports: [SetPasswordComponent],
      providers: [
        provideRouter([]),
        { provide: ConnectionsService, useValue: { setPassword } },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => 'c1' } } } }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(SetPasswordComponent);
    await fixture.whenStable();
    fixture.detectChanges();
  });

  it('renders a focused password card', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('Set password');
    expect(compiled.querySelector('.card')).toBeTruthy();
    expect(compiled.querySelector('#password')?.getAttribute('type')).toBe('password');
    expect(compiled.querySelector('.btn-primary')?.textContent).toContain('Save password');
  });

  it('saves through ConnectionsService.setPassword', async () => {
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const compiled = fixture.nativeElement as HTMLElement;
    const input = compiled.querySelector<HTMLInputElement>('#password');
    expect(input).toBeTruthy();
    input!.value = 'new-secret';
    input!.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    compiled.querySelector<HTMLFormElement>('form')?.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(setPassword).toHaveBeenCalledWith('c1', 'new-secret');
    expect(navigate).toHaveBeenCalledWith(['/']);
  });
});
