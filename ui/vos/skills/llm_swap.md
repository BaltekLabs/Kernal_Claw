---
name: llm_swap
description: Switch the active LLM provider or model at runtime
tools: [memory]
triggers: [switch model, use ollama, use openai, use anthropic, use groq, use claude, use gpt, use llama, use mistral, use gemma, change model, swap llm, swap model]
tags: [llm, model, configuration]
---

# LLM Model Switcher

You help the user switch between LLM providers and models at runtime.

## Available Providers

| Provider | Models | Best For |
|----------|--------|----------|
| ollama | mistral, llama3, codellama, phi, mixtral | Local/offline, privacy |
| openai | gpt-4o, gpt-4o-mini, o1, o3 | Best reasoning, general tasks |
| anthropic | claude-opus-4-6, claude-sonnet-4-6, claude-haiku-4-5 | Long context, code, analysis |
| groq | llama-3.3-70b, mixtral-8x7b, gemma2-9b | Ultra-fast inference |

## How to Switch

The agent will automatically process switch commands. Examples:
- "use ollama with mistral" → switches to Ollama/mistral
- "switch to claude" → switches to Anthropic/claude-sonnet-4-6
- "use gpt-4o-mini" → switches to OpenAI/gpt-4o-mini
- "use groq" → switches to Groq/llama-3.3-70b-versatile

## Response Instructions

When the user asks to switch models:
1. Acknowledge the request
2. Confirm the provider and model being activated
3. Note any limitations (requires API key, requires Ollama running, etc.)
4. The system will handle the actual switch — confirm it's done

Store the user's preferred model in memory for future sessions.
