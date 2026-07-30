{%- comment -%}
  The theme inlines this as a JS expression (`var config = {% include
  mermaid_config.js %};`), so it can be computed at runtime rather than frozen at
  build time. That matters because the colour scheme is chosen in the browser:
  a build-time "neutral" theme would put white diagram blocks on a dark page.

  Diagrams render once, so a mid-page toggle can't restyle them — the toggle in
  header_custom.html reloads the page when a diagram is present.
{%- endcomment -%}
(function () {
  var link = document.querySelector('[rel="stylesheet"]');
  var isDark = !!link && link.getAttribute('href').indexOf('cs30dark') !== -1;
  return {
    theme: isDark ? 'dark' : 'neutral',
    darkMode: isDark,
    fontFamily:
      '-apple-system, BlinkMacSystemFont, Segoe UI, Helvetica, Arial, sans-serif',
    flowchart: { useMaxWidth: true, htmlLabels: true, curve: 'basis' },
    sequence: { useMaxWidth: true },
    er: { useMaxWidth: true },
    themeVariables: isDark
      ? {
          background: '#0f172a',
          primaryColor: '#1e293b',
          primaryTextColor: '#e2e8f0',
          primaryBorderColor: '#3b82f6',
          lineColor: '#64748b',
          secondaryColor: '#141f38',
          tertiaryColor: '#0b1220',
          // Subgraph boxes default to a mid-grey that glares on a dark page.
          clusterBkg: '#0b1220',
          clusterBorder: '#334155',
          titleColor: '#94a3b8',
          edgeLabelBackground: '#0f172a'
        }
      : {
          primaryColor: '#eff6ff',
          primaryTextColor: '#0f172a',
          primaryBorderColor: '#2563eb',
          lineColor: '#64748b',
          clusterBkg: '#f8fafc',
          clusterBorder: '#e2e8f0',
          titleColor: '#475569',
          edgeLabelBackground: '#ffffff'
        }
  };
})()
