/**
 * NexusHome - Official Website Interactive Logic
 * Features:
 * - Hero Real App Screenshot Switcher (Live Control, Dark Mode, Light Mode)
 * - Interactive Full-Resolution Screenshot Lightbox Modal
 * - Mobile Navigation Menu Toggle with responsive backdrop
 * - Dynamic GitHub release details fetcher
 * - Scroll animations & micro-interactions
 */

document.addEventListener('DOMContentLoaded', () => {
  initHeroShowcase();
  initHardwareViewer();
  initMobileNav();
  initLightbox();
  initGitHubRelease();
  initScrollAnimations();
});

/* ===================================================================
   1. Hero Real App Screen Switcher
   =================================================================== */
function initHeroShowcase() {
  const tabs = document.querySelectorAll('.screen-tab');
  const heroImg = document.getElementById('heroAppImg');
  if (!tabs.length || !heroImg) return;

  const screens = {
    connected: 'assets/exampleconnectedscreen.jpg',
    dark: 'assets/darkmodescreen.jpg',
    light: 'assets/lightmodescreen.jpg'
  };

  tabs.forEach(tab => {
    tab.addEventListener('click', () => {
      const screenKey = tab.dataset.screen;
      if (!screens[screenKey]) return;

      // Update active tab styling
      tabs.forEach(t => t.classList.remove('active'));
      tab.classList.add('active');

      // Smooth fade transition
      heroImg.style.opacity = '0';
      heroImg.style.transform = 'scale(0.98)';

      setTimeout(() => {
        heroImg.src = screens[screenKey];
        heroImg.style.opacity = '1';
        heroImg.style.transform = 'scale(1)';
      }, 150);
    });
  });
}

/* ===================================================================
   Hardware Hub Interactive Switcher
   =================================================================== */
function initHardwareViewer() {
  const tabs = document.querySelectorAll('.hw-tab');
  const hwImg = document.getElementById('hwMainImg');
  const hwBadge = document.getElementById('hwBadge');
  const hwTitle = document.getElementById('hwTitle');
  const hwDesc = document.getElementById('hwDesc');

  if (!tabs.length || !hwImg) return;

  const views = {
    front: {
      src: 'assets/NexushomeHardwareFrontView.png',
      badge: 'Exterior Enclosure',
      title: 'Front View & Status Display Enclosure',
      desc: 'Precision custom wall-mounted chassis presenting the SSD1309 2.42" high-contrast OLED screen, system status indicators, and sleek matte finish.'
    },
    inside: {
      src: 'assets/NexushomeHardwareInsidewithLabels.jpg',
      badge: 'High-Resolution Schematics',
      title: 'Hardware Internal Architecture & Components',
      desc: 'Complete inside view featuring the Motolite 12V 7Ah UPS backup battery, isolated 4-channel relay board, VC-02 offline voice module, DHT-22 sensor, buck converters, and 2.42" SSD1309 OLED display.'
    },
    side: {
      src: 'assets/NexushomeHardwareleftsideiffacingfrontview.png',
      badge: 'Thermal Ventilation',
      title: 'Side Profile & Dual Brushless Cooling Fans',
      desc: 'Exhaust side view featuring dual brushless DC cooling fans for continuous thermal regulation of the internal power rail and relay drivers.'
    }
  };

  tabs.forEach(tab => {
    tab.addEventListener('click', () => {
      const key = tab.dataset.hw;
      const data = views[key];
      if (!data) return;

      // Update active state
      tabs.forEach(t => {
        t.classList.remove('active');
        t.setAttribute('aria-selected', 'false');
      });
      tab.classList.add('active');
      tab.setAttribute('aria-selected', 'true');

      // Smooth fade transition
      hwImg.style.opacity = '0';
      hwImg.style.transform = 'scale(0.98)';

      setTimeout(() => {
        hwImg.src = data.src;
        hwImg.alt = data.title;
        if (hwBadge) hwBadge.textContent = data.badge;
        if (hwTitle) hwTitle.textContent = data.title;
        if (hwDesc) hwDesc.textContent = data.desc;

        hwImg.style.opacity = '1';
        hwImg.style.transform = 'scale(1)';
      }, 160);
    });
  });
}

/* ===================================================================
   2. Responsive Mobile Navigation Menu
   =================================================================== */
function initMobileNav() {
  const toggleBtn = document.getElementById('btnNavToggle');
  const navLinks = document.getElementById('navLinks');
  if (!toggleBtn || !navLinks) return;

  function toggleMenu() {
    const isOpen = navLinks.classList.toggle('open');
    toggleBtn.classList.toggle('active', isOpen);
    toggleBtn.setAttribute('aria-expanded', isOpen ? 'true' : 'false');
    document.body.style.overflow = isOpen ? 'hidden' : '';
  }

  function closeMenu() {
    navLinks.classList.remove('open');
    toggleBtn.classList.remove('active');
    toggleBtn.setAttribute('aria-expanded', 'false');
    document.body.style.overflow = '';
  }

  toggleBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    toggleMenu();
  });

  // Close menu when clicking on any link
  navLinks.querySelectorAll('a').forEach(link => {
    link.addEventListener('click', () => {
      closeMenu();
    });
  });

  // Close menu when clicking outside
  document.addEventListener('click', (e) => {
    if (!navLinks.contains(e.target) && !toggleBtn.contains(e.target)) {
      closeMenu();
    }
  });

  // Close on Escape key
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') closeMenu();
  });

  // Auto-close menu if resized above mobile/tablet breakpoint
  window.addEventListener('resize', () => {
    if (window.innerWidth > 1040 && navLinks.classList.contains('open')) {
      closeMenu();
    }
  });
}

/* ===================================================================
   3. Full-Resolution Screenshot Lightbox Modal
   =================================================================== */
function initLightbox() {
  const modal = document.getElementById('imageLightbox');
  const modalImg = document.getElementById('lightboxImg');
  const modalCaption = document.getElementById('lightboxCaption');
  const closeBtn = document.getElementById('lightboxClose');
  const backdrop = document.getElementById('lightboxBackdrop');
  const cards = document.querySelectorAll('.screenshot-card');

  if (!modal || !modalImg) return;

  function openLightbox(src, title) {
    modalImg.src = src;
    if (modalCaption) modalCaption.textContent = title || 'App Screen Preview';
    modal.classList.add('open');
    modal.setAttribute('aria-hidden', 'false');
    document.body.style.overflow = 'hidden'; // Prevent background scrolling
  }

  function closeLightbox() {
    modal.classList.remove('open');
    modal.setAttribute('aria-hidden', 'true');
    document.body.style.overflow = '';
    setTimeout(() => {
      modalImg.src = '';
    }, 200);
  }

  cards.forEach(card => {
    card.addEventListener('click', () => {
      const fullSrc = card.getAttribute('data-full') || card.querySelector('img')?.src;
      const title = card.getAttribute('data-title') || card.querySelector('.screenshot-title')?.textContent;
      if (fullSrc) openLightbox(fullSrc, title);
    });
  });

  // Connect Hardware Hub main image & zoom button to Lightbox
  const hwContainer = document.getElementById('hwViewerContainer');
  const hwZoomBtn = document.getElementById('btnHwZoom');
  const hwImg = document.getElementById('hwMainImg');
  const hwTitle = document.getElementById('hwTitle');

  function openHwLightbox() {
    if (hwImg && hwImg.src) {
      const title = hwTitle ? hwTitle.textContent : 'NexusHome Hardware';
      openLightbox(hwImg.src, title);
    }
  }

  hwContainer?.addEventListener('click', openHwLightbox);
  hwZoomBtn?.addEventListener('click', (e) => {
    e.stopPropagation();
    openHwLightbox();
  });

  closeBtn?.addEventListener('click', closeLightbox);
  backdrop?.addEventListener('click', closeLightbox);

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && modal.classList.contains('open')) {
      closeLightbox();
    }
  });
}

/* ===================================================================
   4. GitHub Latest Release Dynamic Fetcher (Auto-Synced APK Size & Version)
   =================================================================== */
function initGitHubRelease() {
  const repo = 'oursprojects/NexusHome';
  const apiUrl = `https://api.github.com/repos/${repo}/releases/latest`;
  const downloadBtns = document.querySelectorAll('.dynamic-apk-link');
  const versionTags = document.querySelectorAll('.release-version-tag');
  const sizeTags = document.querySelectorAll('.release-size-tag');

  // Fallback defaults
  const fallbackVersion = 'v1.0.4';
  const fallbackUrl = `https://github.com/${repo}/releases/latest/download/app-release.apk`;
  const fallbackSize = '5.45 MB';

  // Apply cached release metadata if available (zero flash, instant display)
  try {
    const cached = localStorage.getItem('nexushome_release_cache');
    if (cached) {
      const data = JSON.parse(cached);
      if (data.version) versionTags.forEach(el => el.textContent = data.version);
      if (data.size) sizeTags.forEach(el => el.textContent = data.size);
      if (data.url) downloadBtns.forEach(btn => btn.setAttribute('href', data.url));
    }
  } catch (e) {
    // ignore
  }

  // Ensure default fallback attributes
  downloadBtns.forEach(btn => {
    if (!btn.getAttribute('href') || btn.getAttribute('href') === '#') {
      btn.setAttribute('href', fallbackUrl);
    }
  });

  fetch(apiUrl)
    .then(res => {
      if (!res.ok) throw new Error('Release fetch status: ' + res.status);
      return res.json();
    })
    .then(data => {
      if (!data) return;
      const tagName = data.tag_name || fallbackVersion;
      versionTags.forEach(el => {
        el.textContent = tagName;
      });

      let downloadUrl = fallbackUrl;
      let sizeFormatted = fallbackSize;

      // Locate APK asset in release assets
      if (Array.isArray(data.assets)) {
        const apkAsset = data.assets.find(a => a.name && a.name.toLowerCase().endsWith('.apk'));
        if (apkAsset) {
          if (apkAsset.browser_download_url) {
            downloadUrl = apkAsset.browser_download_url;
            downloadBtns.forEach(btn => {
              btn.setAttribute('href', downloadUrl);
            });
          }

          if (typeof apkAsset.size === 'number' && apkAsset.size > 0) {
            const mb = (apkAsset.size / (1024 * 1024)).toFixed(2);
            sizeFormatted = `${mb} MB`;
            sizeTags.forEach(el => {
              el.textContent = sizeFormatted;
            });
          }
        }
      }

      // Save to localStorage for instant subsequent loads
      try {
        localStorage.setItem('nexushome_release_cache', JSON.stringify({
          version: tagName,
          size: sizeFormatted,
          url: downloadUrl,
          fetchedAt: Date.now()
        }));
      } catch (e) {
        // ignore
      }
    })
    .catch(() => {
      // Gracefully maintains fallback or cached metadata
    });
}

/* ===================================================================
   5. Clean, Professional Scroll Reveal Animations (Non-Jejemon)
   =================================================================== */
function initScrollAnimations() {
  const elements = document.querySelectorAll(
    '.feature-card, .screenshot-card, .hw-viewer-card, .hw-comp-card, .arch-node, .spec-item, .team-card, .cta-banner'
  );

  elements.forEach(el => {
    el.classList.add('reveal-item');
  });

  const observer = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
      if (entry.isIntersecting) {
        const target = entry.target;
        
        // Gentle micro-stagger for sibling cards in grids (40ms)
        const parent = target.parentElement;
        let delay = 0;
        if (parent && (
          parent.classList.contains('features-grid') || 
          parent.classList.contains('screenshots-grid') || 
          parent.classList.contains('hw-components-grid') || 
          parent.classList.contains('specs-grid') || 
          parent.classList.contains('team-grid')
        )) {
          const siblings = Array.from(parent.children);
          const idx = siblings.indexOf(target);
          if (idx >= 0) delay = Math.min(idx * 40, 200);
        }

        setTimeout(() => {
          target.classList.add('is-revealed');

          // Once the transition finishes, clear transform so CSS :hover runs seamlessly
          setTimeout(() => {
            target.classList.add('reveal-settled');
          }, 500);
        }, delay);

        observer.unobserve(target);
      }
    });
  }, {
    threshold: 0.05,
    rootMargin: '0px 0px -40px 0px'
  });

  elements.forEach(el => observer.observe(el));
}
