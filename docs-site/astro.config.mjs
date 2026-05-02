// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import mdx from '@astrojs/mdx';
import mermaid from 'astro-mermaid';
import starlightOpenAPI, { openAPISidebarGroups } from 'starlight-openapi';
import starlightLinksValidator from 'starlight-links-validator';

// TODO: replace with a custom logo asset once designed.
// See docs: https://starlight.astro.build/reference/configuration/#logo

export default defineConfig({
  site: 'https://samehadar.github.io',
  base: '/orinuno',
  integrations: [
    mermaid({
      theme: 'dark',
      autoTheme: true,
    }),
    starlight({
      title: 'Orinuno',
      description:
        'Standalone open-source service for parsing video content from the Kodik API.',
      defaultLocale: 'root',
      locales: {
        root: { label: 'English', lang: 'en' },
        ru: { label: 'Русский', lang: 'ru' },
      },
      social: [
        {
          icon: 'github',
          label: 'GitHub',
          href: 'https://github.com/Samehadar/orinuno',
        },
      ],
      editLink: {
        baseUrl:
          'https://github.com/Samehadar/orinuno/edit/master/docs-site/',
      },
      customCss: ['./src/styles/custom.css'],
      lastUpdated: true,
      pagination: true,
      plugins: [
        starlightLinksValidator({
          errorOnFallbackPages: false,
          errorOnInconsistentLocale: false,
          exclude: [
            '/orinuno/api/reference/**',
            '/orinuno/api/reference',
            '/api/reference/**',
            '/api/reference',
          ],
        }),
        starlightOpenAPI([
          {
            base: 'api/reference',
            schema: './openapi.json',
            label: 'API Reference',
            collapsed: false,
          },
        ]),
      ],
      sidebar: [
        {
          label: 'Getting Started',
          items: [
            { slug: 'getting-started/quick-start' },
            { slug: 'getting-started/prerequisites' },
            { slug: 'getting-started/configuration' },
          ],
        },
        {
          label: 'Architecture',
          items: [
            { slug: 'architecture/overview' },
            { slug: 'architecture/kodik-api-flow' },
            { slug: 'architecture/video-decoding' },
            { slug: 'architecture/hls-manifest' },
            { slug: 'architecture/video-download' },
            { slug: 'architecture/schema-drift' },
            { slug: 'architecture/parse-requests' },
            { slug: 'architecture/database' },
          ],
        },
        {
          label: 'API',
          items: [
            { slug: 'api/overview' },
            { slug: 'api/embed' },
            ...openAPISidebarGroups,
          ],
        },
        {
          label: 'Operations',
          items: [
            { slug: 'operations/proxy-pool' },
            { slug: 'operations/kodik-tokens' },
            { slug: 'operations/ttl-refresh' },
            { slug: 'operations/background-tasks' },
            { slug: 'operations/monitoring' },
          ],
        },
        {
          label: 'Development',
          items: [
            { slug: 'development/contributing' },
            { slug: 'development/project-structure' },
            { slug: 'development/testing' },
            { slug: 'development/code-style' },
          ],
        },
        {
          label: 'Legal',
          collapsed: true,
          items: [
            { slug: 'legal/disclaimer' },
            { slug: 'legal/responsible-use' },
            { slug: 'legal/takedowns' },
            { slug: 'legal/security-policy' },
            { slug: 'legal/code-of-conduct' },
            { slug: 'legal/license' },
          ],
        },
      ],
    }),
    mdx(),
  ],
});
