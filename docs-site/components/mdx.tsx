import defaultMdxComponents from 'fumadocs-ui/mdx';
import { Accordion, Accordions } from 'fumadocs-ui/components/accordion';
import { Step, Steps } from 'fumadocs-ui/components/steps';
import { Tab, Tabs } from 'fumadocs-ui/components/tabs';
import { TypeTable } from 'fumadocs-ui/components/type-table';
import { File, Files, Folder } from 'fumadocs-ui/components/files';
import type { MDXComponents } from 'mdx/types';
import { AgentGrid, AgentLogo } from '@/components/agents';
import { Kbd } from '@/components/kbd';
import { Check } from '@/components/check';
import { AvatarGallery, AvatarStates } from '@/components/avatars';
import { HostDiagram } from '@/components/host-diagram';

export function getMDXComponents(components?: MDXComponents) {
  return {
    ...defaultMdxComponents,
    Accordion,
    Accordions,
    Step,
    Steps,
    Tab,
    Tabs,
    TypeTable,
    File,
    Files,
    Folder,
    AgentGrid,
    AgentLogo,
    Kbd,
    Check,
    AvatarGallery,
    AvatarStates,
    HostDiagram,
    ...components,
  } satisfies MDXComponents;
}

export const useMDXComponents = getMDXComponents;

declare global {
  type MDXProvidedComponents = ReturnType<typeof getMDXComponents>;
}
