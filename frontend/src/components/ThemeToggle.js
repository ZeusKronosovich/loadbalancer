import React from 'react';
import { IconButton, Tooltip } from '@mui/material';
import { Brightness4, Brightness7 } from '@mui/icons-material';

function ThemeToggle({ theme, toggleTheme }) {
    return (
        <Tooltip title={theme === 'dark' ? 'Переключить на светлую тему' : 'Переключить на тёмную тему'}>
            <IconButton 
                onClick={toggleTheme}
                sx={{
                    color: 'var(--text-secondary)',
                    transition: 'transform 0.3s ease',
                    '&:hover': {
                        transform: 'rotate(30deg)',
                        color: 'var(--text-primary)',
                    }
                }}
            >
                {theme === 'dark' ? <Brightness7 /> : <Brightness4 />}
            </IconButton>
        </Tooltip>
    );
}

export default ThemeToggle;