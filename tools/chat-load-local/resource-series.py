#!/usr/bin/env python3
"""Convert collected resource evidence to numeric gauges; missing stays missing."""
import json
import re
import time


def memory_bytes(value):
    match = re.fullmatch(r'([\d.]+)\s*(B|KiB|MiB|GiB|TiB|kB|MB|GB)', value.strip())
    if not match:
        raise ValueError(value)
    factors = {'B': 1, 'KiB': 1024, 'MiB': 1024**2, 'GiB': 1024**3,
               'TiB': 1024**4, 'kB': 1000, 'MB': 1000**2, 'GB': 1000**3}
    return float(match[1]) * factors[match[2]]


def render(row, run_id):
    lines = []
    def gauge(name, value, **labels):
        labels = {'run_id': run_id, **labels}
        tag = ','.join(k + '=' + json.dumps(v) for k, v in labels.items())
        lines.append(f'{name}{{{tag}}} {value}')
    gauge('chat_local_resource_sample_timestamp_seconds', time.time())
    process = (row.get('k6_cpu_percent_rss_kib') or '').split()
    if len(process) == 2:
        gauge('chat_local_k6_cpu_percent', float(process[0]))
        gauge('chat_local_k6_rss_bytes', int(process[1]) * 1024)
    for container in row.get('docker', []):
        name = container['Name']
        gauge('chat_local_container_cpu_percent', float(container['CPUPerc'].rstrip('%')), container=name)
        used, limit = container['MemUsage'].split('/')
        gauge('chat_local_container_memory_bytes', memory_bytes(used), container=name)
        gauge('chat_local_container_memory_limit_bytes', memory_bytes(limit), container=name)
    cpu = re.search(r'CPU usage:\s*([\d.]+)% user,\s*([\d.]+)% sys,\s*([\d.]+)% idle', row.get('mac_cpu') or '')
    if cpu:
        gauge('chat_local_mac_cpu_busy_percent', 100 - float(cpu[3]))
    vm = row.get('mac_vm_stat') or ''
    page = re.search(r'page size of (\d+) bytes', vm)
    if page:
        for label, count in re.findall(r'^([^:\n]+):\s+(\d+)\.', vm, re.M):
            if label in ['Pages free', 'Pages active', 'Pages inactive', 'Pages wired down', 'Pages occupied by compressor']:
                gauge('chat_local_mac_memory_bytes', int(count) * int(page[1]), kind=label)
    swap = re.search(r'used = ([\d.]+)([MG])', row.get('mac_swap') or '')
    if swap:
        gauge('chat_local_mac_swap_used_bytes', float(swap[1]) * (1024**2 if swap[2] == 'M' else 1024**3))
    return '\n'.join(lines) + '\n'
