import React, { useState, useEffect } from 'react';
import {
    Container,
    Box,
    Typography,
    ThemeProvider,
    createTheme,
    CssBaseline
} from '@mui/material';
import Dashboard from './components/Dashboard';
import ConnectionStatus from './components/ConnectionStatus';
import ThemeToggle from './components/ThemeToggle';
import { useWebSocket } from './hooks/useWebSocket';

// Создаем тему для MUI (адаптируется под CSS переменные)
const getTheme = (mode) => createTheme({
    palette: {
        mode: mode,
        primary: { main: '#3b82f6' },
        secondary: { main: '#22c55e' },
        background: {
            default: mode === 'dark' ? '#0f1117' : '#f4f7fc',
            paper: mode === 'dark' ? '#1e212a' : '#ffffff',
        },
        text: {
            primary: mode === 'dark' ? '#e8edf5' : '#1e293b',
            secondary: mode === 'dark' ? '#94a3b8' : '#475569',
        },
    },
    typography: {
        fontFamily: '"Segoe UI", "Roboto", "Helvetica", sans-serif',
    },
    shape: {
        borderRadius: 12,
    },
});

function App() {
    const { isConnected, data, sendCommand } = useWebSocket();
    
    const [themeMode, setThemeMode] = useState(() => {
        const saved = localStorage.getItem('themeMode');
        return saved || 'dark';
    });

    const muiTheme = getTheme(themeMode);

    useEffect(() => {
        localStorage.setItem('themeMode', themeMode);
        document.documentElement.setAttribute('data-theme', themeMode);
    }, [themeMode]);

    const toggleTheme = () => {
        setThemeMode(prev => prev === 'dark' ? 'light' : 'dark');
    };

    return (
        <ThemeProvider theme={muiTheme}>
            <CssBaseline />
            <div className="dashboard-container">
                <Container maxWidth="xl">
                    <div className="dashboard-header">
                        <div className="dashboard-header-left">
                            <ConnectionStatus isConnected={isConnected} />
                        </div>
                        <ThemeToggle theme={themeMode} toggleTheme={toggleTheme} />
                    </div>

                    <Dashboard 
                        strategy={data.strategy}
                        backends={data.backends}
                        stats={data.stats}
                        isConnected={isConnected}
                        sendCommand={sendCommand}
                    />
                </Container>
            </div>
        </ThemeProvider>
    );
}

export default App;