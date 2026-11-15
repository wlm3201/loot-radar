/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        ink: {
          900: '#0B1220',
          800: '#111A2B',
          700: '#1E293B',
          600: '#263449',
        },
        accent: {
          DEFAULT: '#22D3EE',
          deep: '#06B6D4',
          dark: '#0E7490',
        },
        good: '#34D399',
        warn: '#FBBF24',
        bad: '#F87171',
        info: '#7DD3FC',
      },
      fontFamily: {
        sans: ['"Noto Sans SC"', 'system-ui', 'sans-serif'],
      },
    },
  },
  plugins: [],
}
