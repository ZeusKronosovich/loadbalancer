import React, { useRef, useEffect, useState } from 'react';
import { Chart as ChartJS, ArcElement, Tooltip, Legend, CategoryScale, LinearScale, PointElement, LineElement, Filler, BarElement } from 'chart.js';
import { Doughnut, Line } from 'react-chartjs-2';
import { Box, Typography, Paper, Chip, useTheme } from '@mui/material';
import { styled } from '@mui/material/styles';

ChartJS.register(ArcElement, Tooltip, Legend, CategoryScale, LinearScale, PointElement, LineElement, Filler, BarElement);

const StyledChartContainer = styled(Paper)(({ theme }) => ({
    padding: theme.spacing(2),
    height: '320px',
    position: 'relative',
    transition: 'all 0.3s ease',
    '&:hover': {
        boxShadow: theme.shadows[4],
    },
}));

const MetricChip = styled(Chip)(({ theme }) => ({
    position: 'absolute',
    top: theme.spacing(1),
    right: theme.spacing(2),
    fontWeight: 600,
}));

function Charts({ backends, type }) {
    const theme = useTheme();
    const [history, setHistory] = useState([]);
    const [responseTimeHistory, setResponseTimeHistory] = useState([]);
    const [maxHistory, setMaxHistory] = useState(50);

    useEffect(() => {
        if (backends.length > 0) {
            const total = backends.reduce((sum, b) => sum + b.activeConnections, 0);
            const now = new Date().toLocaleTimeString('ru-RU', {
                hour: '2-digit',
                minute: '2-digit',
                second: '2-digit'
            });
            
            setHistory(prev => {
                const newHistory = [...prev];
                if (newHistory.length > 0 && newHistory[newHistory.length - 1].time === now) {
                    newHistory[newHistory.length - 1] = { time: now, value: total };
                    return newHistory;
                }
                newHistory.push({ time: now, value: total });
                if (newHistory.length > maxHistory) newHistory.shift();
                return newHistory;
            });

            const aliveBackends = backends.filter(b => b.alive && b.averageResponseTime > 0);
            if (aliveBackends.length > 0) {
                const avgRT = aliveBackends.reduce((sum, b) => sum + b.averageResponseTime, 0) / aliveBackends.length;
                setResponseTimeHistory(prev => {
                    const newHistory = [...prev];
                    if (newHistory.length > 0 && newHistory[newHistory.length - 1].time === now) {
                        newHistory[newHistory.length - 1] = { time: now, value: Math.round(avgRT) };
                        return newHistory;
                    }
                    newHistory.push({ time: now, value: Math.round(avgRT) });
                    if (newHistory.length > maxHistory) newHistory.shift();
                    return newHistory;
                });
            }
        }
    }, [backends, maxHistory]);

    if (type === 'distribution') {
        const colors = ['#3b82f6', '#22c55e', '#f59e0b', '#ef4444', '#8b5cf6', '#ec4899', '#14b8a6'];
        const labels = backends.map(b => `${b.host}:${b.port}`);
        const data = backends.map(b => b.activeConnections);
        const totalConnections = data.reduce((sum, val) => sum + val, 0);

        const chartData = {
            labels: labels,
            datasets: [{
                data: data,
                backgroundColor: colors.slice(0, labels.length),
                borderColor: '#ffffff',
                borderWidth: 3,
                hoverOffset: 15,
            }]
        };

        const options = {
            responsive: true,
            maintainAspectRatio: false,
            cutout: '65%',
            plugins: {
                legend: {
                    position: 'bottom',
                    labels: {
                        padding: 15,
                        usePointStyle: true,
                        pointStyle: 'circle',
                        font: {
                            size: 12,
                        },
                    },
                },
                tooltip: {
                    callbacks: {
                        label: function(context) {
                            const label = context.label || '';
                            const value = context.parsed || 0;
                            const percentage = totalConnections > 0 
                                ? ((value / totalConnections) * 100).toFixed(1) 
                                : 0;
                            return `${label}: ${value} соединений (${percentage}%)`;
                        }
                    }
                }
            },
            animation: {
                animateRotate: true,
                duration: 1000,
            },
        };

        const centerText = {
            id: 'centerText',
            beforeDraw: function(chart) {
                const { width, height, ctx } = chart;
                ctx.save();
                const centerX = width / 2;
                const centerY = height / 2 - 10;
                
                ctx.shadowColor = 'rgba(0, 0, 0, 0.1)';
                ctx.shadowBlur = 10;
                
                ctx.font = 'bold 22px "Segoe UI", sans-serif';
                ctx.fillStyle = '#1e293b';
                ctx.textAlign = 'center';
                ctx.textBaseline = 'middle';
                ctx.fillText(totalConnections, centerX, centerY - 8);
                
                ctx.font = '12px "Segoe UI", sans-serif';
                ctx.fillStyle = '#94a3b8';
                
                ctx.restore();
            }
        };

    }

    if (type === 'responseTime') {
        const labels = responseTimeHistory.map(d => d.time);
        const data = responseTimeHistory.map(d => d.value);
        const maxValue = Math.max(100, ...data) * 1.2;

        const chartData = {
            labels: labels,
            datasets: [{
                label: 'Среднее время ответа (мс)',
                data: data,
                borderColor: '#ec4899',
                backgroundColor: (context) => {
                    const chart = context.chart;
                    const { ctx, chartArea } = chart;
                    if (!chartArea) return 'rgba(236, 72, 153, 0.2)';
                    const gradient = ctx.createLinearGradient(0, chartArea.top, 0, chartArea.bottom);
                    gradient.addColorStop(0, 'rgba(236, 72, 153, 0.4)');
                    gradient.addColorStop(0.5, 'rgba(236, 72, 153, 0.1)');
                    gradient.addColorStop(1, 'rgba(236, 72, 153, 0.0)');
                    return gradient;
                },
                fill: true,
                tension: 0.4,
                pointRadius: (context) => {
                    return context.dataIndex === context.dataset.data.length - 1 ? 6 : 3;
                },
                pointBackgroundColor: (context) => {
                    return context.dataIndex === context.dataset.data.length - 1 
                        ? '#ec4899' 
                        : 'rgba(236, 72, 153, 0.6)';
                },
                pointBorderColor: '#ffffff',
                pointBorderWidth: 2,
                borderWidth: 3,
            }]
        };

        const options = {
            responsive: true,
            maintainAspectRatio: false,
            animation: {
                duration: 500,
            },
            interaction: {
                intersect: false,
                mode: 'index',
            },
            scales: {
                y: {
                    beginAtZero: true,
                    max: maxValue,
                    grid: {
                        color: 'rgba(0, 0, 0, 0.05)',
                        drawBorder: false,
                    },
                    ticks: {
                        font: {
                            size: 11,
                        },
                    },
                },
                x: {
                    grid: {
                        display: false,
                    },
                    ticks: {
                        maxTicksLimit: 12,
                        font: {
                            size: 10,
                        },
                        maxRotation: 45,
                        minRotation: 0,
                    },
                }
            },
            plugins: {
                legend: {
                    display: false,
                },
                tooltip: {
                    backgroundColor: 'rgba(255, 255, 255, 0.95)',
                    titleColor: '#1e293b',
                    bodyColor: '#475569',
                    borderColor: '#e2e8f0',
                    borderWidth: 1,
                    padding: 12,
                    callbacks: {
                        label: function(context) {
                            return `Время ответа: ${context.parsed.y}ms`;
                        }
                    }
                }
            },
        };
    }

    if (type === 'history') {
        const labels = history.map(d => d.time);
        const data = history.map(d => d.value);
        const maxValue = Math.max(10, ...data) * 1.2;

        const chartData = {
            labels: labels,
            datasets: [{
                label: 'Активные соединения',
                data: data,
                borderColor: '#3b82f6',
                backgroundColor: (context) => {
                    const chart = context.chart;
                    const { ctx, chartArea } = chart;
                    if (!chartArea) return 'rgba(59, 130, 246, 0.2)';
                    const gradient = ctx.createLinearGradient(0, chartArea.top, 0, chartArea.bottom);
                    gradient.addColorStop(0, 'rgba(59, 130, 246, 0.4)');
                    gradient.addColorStop(0.5, 'rgba(59, 130, 246, 0.1)');
                    gradient.addColorStop(1, 'rgba(59, 130, 246, 0.0)');
                    return gradient;
                },
                fill: true,
                tension: 0.4,
                pointRadius: (context) => {
                    return context.dataIndex === context.dataset.data.length - 1 ? 6 : 3;
                },
                pointBackgroundColor: (context) => {
                    return context.dataIndex === context.dataset.data.length - 1 
                        ? '#3b82f6' 
                        : 'rgba(59, 130, 246, 0.6)';
                },
                pointBorderColor: '#ffffff',
                pointBorderWidth: 2,
                borderWidth: 3,
            }]
        };

        const options = {
            responsive: true,
            maintainAspectRatio: false,
            animation: {
                duration: 500,
            },
            interaction: {
                intersect: false,
                mode: 'index',
            },
            scales: {
                y: {
                    beginAtZero: true,
                    max: maxValue,
                    grid: {
                        color: 'rgba(0, 0, 0, 0.05)',
                        drawBorder: false,
                    },
                    ticks: {
                        stepSize: Math.ceil(maxValue / 6),
                        font: {
                            size: 11,
                        },
                    },
                },
                x: {
                    grid: {
                        display: false,
                    },
                    ticks: {
                        maxTicksLimit: 12,
                        font: {
                            size: 10,
                        },
                        maxRotation: 45,
                        minRotation: 0,
                    },
                }
            },
            plugins: {
                legend: {
                    display: false,
                },
                tooltip: {
                    backgroundColor: 'rgba(255, 255, 255, 0.95)',
                    titleColor: '#1e293b',
                    bodyColor: '#475569',
                    borderColor: '#e2e8f0',
                    borderWidth: 1,
                    padding: 12,
                    callbacks: {
                        label: function(context) {
                            return `Соединений: ${context.parsed.y}`;
                        }
                    }
                }
            },
        };

        const currentValuePlugin = {
            id: 'currentValue',
            afterDraw: function(chart) {
                const { ctx, data, chartArea: { top, bottom, left, right } } = chart;
                const lastIndex = data.datasets[0].data.length - 1;
                if (lastIndex < 0) return;
                
                const lastValue = data.datasets[0].data[lastIndex];
                const meta = chart.getDatasetMeta(0);
                const point = meta.data[lastIndex];
                
                if (!point) return;
                
                ctx.save();
                const x = point.x;
                const y = point.y - 30;
                
                ctx.font = 'bold 13px "Segoe UI", sans-serif';
                ctx.fillStyle = '#1e293b';
                ctx.textAlign = 'center';
                ctx.textBaseline = 'bottom';
                
                const text = `${lastValue}`;
                const metrics = ctx.measureText(text);
                const padding = 8;
                const rectWidth = metrics.width + padding * 2;
                const rectHeight = 26;
                const rectX = x - rectWidth / 2;
                const rectY = y - rectHeight;
                
                ctx.shadowColor = 'rgba(0, 0, 0, 0.1)';
                ctx.shadowBlur = 8;
                ctx.fillStyle = 'rgba(255, 255, 255, 0.95)';
                ctx.beginPath();
                if (ctx.roundRect) {
                    ctx.roundRect(rectX, rectY, rectWidth, rectHeight, 6);
                } else {
                    ctx.rect(rectX, rectY, rectWidth, rectHeight);
                }
                ctx.fill();
                ctx.shadowBlur = 0;
                
                ctx.fillStyle = '#3b82f6';
                ctx.fillText(text, x, y - 2);
                ctx.restore();
            }
        };

    }

    return null;
}

export default Charts;
