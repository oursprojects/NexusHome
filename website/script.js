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
  initFirmwareCodeViewer();
  initScreenshotsCarousel();
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
  const hwContainer = document.getElementById('hwViewerContainer');
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
    },
    blueprint: {
      src: 'assets/NexusHomeMainBoard.jpg',
      badge: 'Wiring Reference Blueprint',
      title: 'ESP32 Mainboard Blueprint & Pin Connections',
      desc: 'Official terminal-to-pin wiring schematic detailing the ESP32 breakout module, OLED headers, 4-channel relay connections (D15 Fan, D2 Light, D18/19 Curtains), VC-02 serial lines, buzzer, and the 10kΩ DHT22 pull-up resistor.'
    }
  };

  // 1. Proactively preload all 4 images into browser memory immediately
  Object.values(views).forEach(v => {
    const p = new Image();
    p.src = v.src;
  });

  let currentSwitchId = 0;

  tabs.forEach(tab => {
    tab.addEventListener('click', () => {
      const key = tab.dataset.hw;
      const data = views[key];
      if (!data) return;

      // Update active tab styling
      tabs.forEach(t => {
        t.classList.remove('active');
        t.setAttribute('aria-selected', 'false');
      });
      tab.classList.add('active');
      tab.setAttribute('aria-selected', 'true');

      // Update text content immediately
      if (hwBadge) hwBadge.textContent = data.badge;
      if (hwTitle) hwTitle.textContent = data.title;
      if (hwDesc) hwDesc.textContent = data.desc;

      // Check if same image is already loaded
      const curSrc = hwImg.getAttribute('src');
      if (curSrc === data.src && hwImg.complete) {
        hwImg.style.opacity = '1';
        hwImg.style.filter = 'none';
        return;
      }

      const switchId = ++currentSwitchId;

      // Show smooth fade and loading spinner
      hwImg.style.opacity = '0.35';
      hwImg.style.filter = 'blur(3px)';
      if (hwContainer) hwContainer.classList.add('hw-loading');

      // Load via Image object to ensure bytes arrive before swap
      const loader = new Image();
      loader.onload = () => {
        if (switchId !== currentSwitchId) return; // Stale click ignored
        hwImg.src = data.src;
        hwImg.alt = data.title;
        if (hwContainer) hwContainer.classList.remove('hw-loading');
        hwImg.style.opacity = '1';
        hwImg.style.filter = 'none';
        hwImg.style.transform = 'scale(1)';
      };

      loader.onerror = () => {
        if (switchId !== currentSwitchId) return;
        hwImg.src = data.src;
        if (hwContainer) hwContainer.classList.remove('hw-loading');
        hwImg.style.opacity = '1';
        hwImg.style.filter = 'none';
      };

      loader.src = data.src;
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
  const fallbackVersion = 'v1.0.5';
  const fallbackUrl = `https://github.com/${repo}/releases/latest/download/app-release.apk`;
  const fallbackSize = '5.47 MB';

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
          parent.classList.contains('screenshots-carousel') ||
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

/* ===================================================================
   6. ESP32 Firmware Interactive Code Viewer
   =================================================================== */
function initFirmwareCodeViewer() {
  const rawScript = document.getElementById('firmwareCodeRaw');
  const codeInner = document.getElementById('firmwareCodeInner');
  if (!rawScript || !codeInner) return;

  const rawCode = rawScript.textContent.trim();
  const lines = rawCode.split('\n');

  function escapeHtml(str) {
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function highlightTokens(text) {
    if (!text) return '';
    const escaped = escapeHtml(text);
    return escaped
      .replace(/(^\s*#\s*[a-zA-Z_]+)/gm, '<span class="hl-preproc">$1</span>')
      .replace(/(&quot;.*?&quot;|&#39;.*?&#39;|'.*?')/g, '<span class="hl-str">$1</span>')
      .replace(/\b(constexpr|void|bool|float|uint8_t|uint32_t|int|int32_t|char|const|static|enum|struct|class|if|else|while|for|return|switch|case|default|break|continue|true|false|nullptr)\b/g, '<span class="hl-keyword">$1</span>')
      .replace(/\b(DHT|Wire|U8g2lib|HardwareSerial|BluetoothSerial|Preferences|String|U8G2_SSD1309_128X64_NONAME0_F_HW_I2C|CurtainState|Serial|SerialBT|VC02|dht|display|preferences)\b/g, '<span class="hl-type">$1</span>')
      .replace(/\b(HIGH|LOW|OUTPUT|INPUT|SERIAL_8N1|U8G2_R0|U8X8_PIN_NONE|DHT22|DHTPIN|RELAY_LIGHT|RELAY_FAN|RELAY_CURTAIN_OPEN|RELAY_CURTAIN_CLOSE|BUZZER_PIN|VC_RX|VC_TX|CMD_[A-Z0-9_]+|C_[A-Z0-9_]+|TEMP_[A-Z0-9_]+)\b/g, '<span class="hl-const">$1</span>')
      .replace(/\b(0x[0-9a-fA-F]+|\d+\.?\d*f?)\b/g, '<span class="hl-num">$1</span>');
  }

  // Parse code and render lines
  let inBlockComment = false;
  const lineHtmls = [];

  for (let i = 0; i < lines.length; i++) {
    const lineNum = i + 1;
    const line = lines[i];
    let processed = '';

    if (inBlockComment) {
      const endIdx = line.indexOf('*/');
      if (endIdx !== -1) {
        processed = '<span class="hl-comment">' + escapeHtml(line.slice(0, endIdx + 2)) + '</span>' + highlightTokens(line.slice(endIdx + 2));
        inBlockComment = false;
      } else {
        processed = '<span class="hl-comment">' + escapeHtml(line) + '</span>';
      }
    } else {
      const startIdx = line.indexOf('/*');
      const lineIdx = line.indexOf('//');

      if (startIdx !== -1 && (lineIdx === -1 || startIdx < lineIdx)) {
        const endIdx = line.indexOf('*/', startIdx + 2);
        if (endIdx !== -1) {
          processed = highlightTokens(line.slice(0, startIdx)) + '<span class="hl-comment">' + escapeHtml(line.slice(startIdx, endIdx + 2)) + '</span>' + highlightTokens(line.slice(endIdx + 2));
        } else {
          processed = highlightTokens(line.slice(0, startIdx)) + '<span class="hl-comment">' + escapeHtml(line.slice(startIdx)) + '</span>';
          inBlockComment = true;
        }
      } else if (lineIdx !== -1) {
        processed = highlightTokens(line.slice(0, lineIdx)) + '<span class="hl-comment">' + escapeHtml(line.slice(lineIdx)) + '</span>';
      } else {
        processed = highlightTokens(line);
      }
    }

    lineHtmls.push(
      '<div class="code-line" id="codeLine' + lineNum + '" data-line="' + lineNum + '">' +
      '<span class="line-num">' + lineNum + '</span>' +
      '<span class="line-code">' + (processed || ' ') + '</span>' +
      '</div>'
    );
  }

  codeInner.innerHTML = lineHtmls.join('');

  // 1. Copy Code Button
  const btnCopy = document.getElementById('btnCopyFirmware');
  const copyLabel = document.getElementById('copyLabel');
  const copyIcon = document.getElementById('copyIcon');

  if (btnCopy) {
    btnCopy.addEventListener('click', async () => {
      try {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          await navigator.clipboard.writeText(rawCode);
        } else {
          const ta = document.createElement('textarea');
          ta.value = rawCode;
          ta.style.position = 'fixed';
          ta.style.opacity = '0';
          document.body.appendChild(ta);
          ta.select();
          document.execCommand('copy');
          document.body.removeChild(ta);
        }

        btnCopy.classList.add('btn-code-copied');
        if (copyLabel) copyLabel.textContent = 'Copied!';
        if (copyIcon) {
          copyIcon.innerHTML = '<polyline points="20 6 9 17 4 12"></polyline>';
        }

        setTimeout(() => {
          btnCopy.classList.remove('btn-code-copied');
          if (copyLabel) copyLabel.textContent = 'Copy Code';
          if (copyIcon) {
            copyIcon.innerHTML = '<rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>';
          }
        }, 2200);
      } catch (err) {
        console.error('Failed to copy code: ', err);
      }
    });
  }

  // 2. Fullscreen / Expand Viewer
  const btnExpand = document.getElementById('btnExpandFirmware');
  const viewerWrapper = document.getElementById('firmwareCodeViewer');
  const labelExpand = document.getElementById('labelExpand');
  const iconExpand = document.getElementById('iconExpand');

  if (btnExpand && viewerWrapper) {
    btnExpand.addEventListener('click', () => {
      viewerWrapper.classList.toggle('code-viewer-expanded');
      const isExpanded = viewerWrapper.classList.contains('code-viewer-expanded');

      if (labelExpand) {
        labelExpand.textContent = isExpanded ? 'Collapse' : 'Expand View';
      }
      if (iconExpand) {
        if (isExpanded) {
          iconExpand.innerHTML = '<polyline points="4 14 10 14 10 20"></polyline><polyline points="20 10 14 10 14 4"></polyline><line x1="14" y1="10" x2="21" y2="3"></line><line x1="3" y1="21" x2="10" y2="14"></line>';
        } else {
          iconExpand.innerHTML = '<polyline points="15 3 21 3 21 9"></polyline><polyline points="9 21 3 21 3 15"></polyline><line x1="21" y1="3" x2="14" y2="10"></line><line x1="3" y1="21" x2="10" y2="14"></line>';
        }
      }
    });

    // Close on Escape key
    window.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && viewerWrapper.classList.contains('code-viewer-expanded')) {
        viewerWrapper.classList.remove('code-viewer-expanded');
        if (labelExpand) labelExpand.textContent = 'Expand View';
        if (iconExpand) {
          iconExpand.innerHTML = '<polyline points="15 3 21 3 21 9"></polyline><polyline points="9 21 3 21 3 15"></polyline><line x1="21" y1="3" x2="14" y2="10"></line><line x1="3" y1="21" x2="10" y2="14"></line>';
        }
      }
    });
  }

  // 3. Quick-Jump Pills
  const viewport = document.getElementById('firmwareViewport');
  const jumpPills = document.querySelectorAll('.code-jump-pill');

  jumpPills.forEach(pill => {
    pill.addEventListener('click', () => {
      const targetLine = parseInt(pill.dataset.targetLine, 10);
      const targetEl = document.getElementById('codeLine' + targetLine);
      if (targetEl && viewport) {
        // Scroll viewport to line
        const elTop = targetEl.offsetTop;
        viewport.scrollTo({
          top: Math.max(0, elTop - 30),
          behavior: 'smooth'
        });

        // Flash target line
        document.querySelectorAll('.code-line.line-target-highlight').forEach(el => {
          el.classList.remove('line-target-highlight');
        });
        targetEl.classList.add('line-target-highlight');
        setTimeout(() => {
          targetEl.classList.remove('line-target-highlight');
        }, 2200);
      }
    });
  });

  // 4. Code Search / Filter
  const searchInput = document.getElementById('codeSearchInput');
  const searchCount = document.getElementById('codeSearchCount');

  if (searchInput) {
    searchInput.addEventListener('input', () => {
      const query = searchInput.value.trim().toLowerCase();
      let matchCount = 0;
      let firstMatchEl = null;

      const lineEls = codeInner.querySelectorAll('.code-line');
      lineEls.forEach((lineEl, idx) => {
        const lineText = lines[idx].toLowerCase();
        if (query.length >= 2 && lineText.includes(query)) {
          lineEl.classList.add('line-search-hit');
          matchCount++;
          if (!firstMatchEl) firstMatchEl = lineEl;
        } else {
          lineEl.classList.remove('line-search-hit');
        }
      });

      if (searchCount) {
        searchCount.textContent = query.length >= 2 ? (matchCount + ' found') : '';
      }

      if (firstMatchEl && viewport && query.length >= 2) {
        viewport.scrollTo({
          top: Math.max(0, firstMatchEl.offsetTop - 50),
          behavior: 'smooth'
        });
      }
    });
  }
}

/* ===================================================================
   7. App Interface Interactive Responsive Carousel / Slider
   =================================================================== */
function initScreenshotsCarousel() {
  const track = document.getElementById('screenshotsTrack');
  const btnPrev = document.getElementById('btnScreensPrev');
  const btnNext = document.getElementById('btnScreensNext');
  const dots = document.querySelectorAll('#screenshotsDots .carousel-dot');
  const counter = document.getElementById('screenshotsCounter');
  const cards = document.querySelectorAll('#screenshotsTrack .screenshot-card');

  if (!track || !cards.length) return;

  function getActiveIndex() {
    const scrollLeft = track.scrollLeft;
    const viewCenter = scrollLeft + track.offsetWidth / 2;
    let closestIdx = 0;
    let minDistance = Infinity;

    cards.forEach((card, idx) => {
      const cardCenter = card.offsetLeft + card.offsetWidth / 2;
      const dist = Math.abs(cardCenter - viewCenter);
      if (dist < minDistance) {
        minDistance = dist;
        closestIdx = idx;
      }
    });

    return closestIdx;
  }

  function updateActiveState() {
    const activeIdx = getActiveIndex();

    dots.forEach((dot, idx) => {
      dot.classList.toggle('active', idx === activeIdx);
    });

    if (counter) {
      counter.textContent = `Screen ${activeIdx + 1} of ${cards.length}`;
    }
  }

  function scrollToCard(idx) {
    if (cards[idx]) {
      const card = cards[idx];
      const offset = (track.offsetWidth - card.offsetWidth) / 2;
      track.scrollTo({
        left: Math.max(0, card.offsetLeft - offset),
        behavior: 'smooth'
      });
    }
  }

  btnPrev?.addEventListener('click', () => {
    const curIdx = getActiveIndex();
    const prevIdx = Math.max(0, curIdx - 1);
    scrollToCard(prevIdx);
  });

  btnNext?.addEventListener('click', () => {
    const curIdx = getActiveIndex();
    const nextIdx = Math.min(cards.length - 1, curIdx + 1);
    scrollToCard(nextIdx);
  });

  dots.forEach((dot, idx) => {
    dot.addEventListener('click', () => scrollToCard(idx));
  });

  let scrollDebounce;
  track.addEventListener('scroll', () => {
    clearTimeout(scrollDebounce);
    scrollDebounce = setTimeout(updateActiveState, 50);
  }, { passive: true });

  updateActiveState();
}


