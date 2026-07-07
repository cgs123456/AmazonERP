<script setup lang="ts">
import Toast from './components/Toast.vue'
</script>

<template>
  <router-view />
  <Toast ref="toastRef" />
</template>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  background-color: #f8f8f8;
}
</style>
